package app.lyrify.ui

import android.app.UiModeManager
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lyrify.settings.DisplaySettings
import app.lyrify.settings.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The theme equals the prototype's design tokens (player-design/lyrify-prototype.html, lines 6 to 119; the table in
 * handoff/design-fidelity.md): every colour, light and dark, on every Android version, and every text role.
 */
class ThemeTokensTest {
    private fun hex(color: Color): String = String.format("#%08X", color.toArgb())

    private fun assertColor(name: String, expected: String, actual: Color) = assertEquals(name, expected, hex(actual))

    private fun source(name: String): String = File("src/main/java/app/lyrify/ui/$name").readText()

    /** The prototype's app scheme, as name to #AARRGGBB, with the ColorScheme field it maps to. */
    private fun scheme(s: ColorScheme) = mapOf(
        "surface" to s.surface,
        "surface (background)" to s.background,
        "sc-lowest" to s.surfaceContainerLowest,
        "sc-low" to s.surfaceContainerLow,
        "sc" to s.surfaceContainer,
        "sc-high" to s.surfaceContainerHigh,
        "sc-highest" to s.surfaceContainerHighest,
        "on-surface" to s.onSurface,
        "on-var" to s.onSurfaceVariant,
        "outline" to s.outline,
        "outline-var" to s.outlineVariant,
        "primary-c" to s.primaryContainer,
        "on-primary-c" to s.onPrimaryContainer,
        "error" to s.error,
        "on-error" to s.onError,
        "error-c" to s.errorContainer,
        "on-error-c" to s.onErrorContainer,
    )

    private val light = mapOf(
        "surface" to "#FFFCF8FF", "surface (background)" to "#FFFCF8FF", "sc-lowest" to "#FFFFFFFF", "sc-low" to "#FFF7F2FC",
        "sc" to "#FFF1ECF7", "sc-high" to "#FFEBE6F2", "sc-highest" to "#FFE5E0EC", "on-surface" to "#FF1C1A23",
        "on-var" to "#FF49454F", "outline" to "#FF7A7581", "outline-var" to "#FFCBC4D2", "primary-c" to "#FFE5DEFF",
        "on-primary-c" to "#FF1A0F5C", "error" to "#FFBA1A1A", "on-error" to "#FFFFFFFF", "error-c" to "#FFFFDAD6",
        "on-error-c" to "#FF410002",
    )

    private val dark = mapOf(
        "surface" to "#FF13121A", "surface (background)" to "#FF13121A", "sc-lowest" to "#FF0E0D14", "sc-low" to "#FF1B1A22",
        "sc" to "#FF201E27", "sc-high" to "#FF2A2832", "sc-highest" to "#FF35333D", "on-surface" to "#FFE6E1EC",
        "on-var" to "#FFCAC4D0", "outline" to "#FF948F9A", "outline-var" to "#FF49454F", "primary-c" to "#FF3F3590",
        "on-primary-c" to "#FFE5DEFF", "error" to "#FFFFB4AB", "on-error" to "#FF690005", "error-c" to "#FF93000A",
        "on-error-c" to "#FFFFDAD6",
    )

    @Test
    fun `the app scheme is the prototype's, light and dark`() {
        scheme(appColors(dark = false)).forEach { (name, color) -> assertColor("light $name", light.getValue(name), color) }
        scheme(appColors(dark = true)).forEach { (name, color) -> assertColor("dark $name", dark.getValue(name), color) }
    }

    @Test
    fun `the scheme is fixed, with no wallpaper colours on any Android version`() {
        assertSame(LightColors, appColors(dark = false))
        assertSame(DarkColors, appColors(dark = true))
    }

    @Test
    fun `the sung line coral and its container are the prototype's`() {
        assertColor("light hl", "#FFC9472C", LightBrandColors.highlight)
        assertColor("light on-hl", "#FFFFFFFF", LightBrandColors.onHighlight)
        assertColor("light hl-c", "#FFFFDBD1", LightBrandColors.highlightContainer)
        assertColor("light on-hl-c", "#FF3B0A00", LightBrandColors.onHighlightContainer)
        assertColor("dark hl", "#FFFF9A80", DarkBrandColors.highlight)
        assertColor("dark on-hl", "#FF4A1205", DarkBrandColors.onHighlight)
        assertColor("dark hl-c", "#FF7F2B17", DarkBrandColors.highlightContainer)
        assertColor("dark on-hl-c", "#FFFFDBD1", DarkBrandColors.onHighlightContainer)
    }

    @Test
    fun `the chrome colours are the prototype's chrome v3 tokens`() {
        val l = chromeColors(LightColors, LightBrandColors, dark = false)
        assertColor("light action", "#FF2E2466", l.action)
        assertColor("light on-action", "#FFF4F1FF", l.onAction)
        assertColor("light toast", "#FF2E2466", l.toast)
        assertColor("light on-toast", "#FFF4F1FF", l.onToast)
        assertColor("light toast-act", "#FFFF8A70", l.toastAction)
        assertEquals("light glass", 0.87f, l.glass.alpha, 0.006f)
        assertColor("light glass tint", "#FFF1ECF7", l.glass.copy(alpha = 1f))
        assertEquals("light glass-solid", 0.97f, l.glassSolid.alpha, 0.003f)
        assertColor("light glass-edge", hex(Color.White.copy(alpha = 0.6f)), l.glassEdge)
        assertColor("light c-shadow", hex(Color(0xFF1A143C).copy(alpha = 0.18f)), l.shadow)
        assertColor("light shadow-strong", hex(Color(0xFF1A143C).copy(alpha = 0.38f)), l.shadowStrong)
        assertColor("light c-scrim", hex(Color(0xFF1A143C).copy(alpha = 0.36f)), l.scrim)
        assertColor("light sheet", "#FFFFFFFF", l.sheet)

        val d = chromeColors(DarkColors, DarkBrandColors, dark = true)
        assertColor("dark action", "#FFE5DEFF", d.action)
        assertColor("dark on-action", "#FF1A0F5C", d.onAction)
        assertColor("dark toast", "#FF2E2466", d.toast)
        assertColor("dark toast-edge", "#FF5A50AE", d.toastEdge)
        assertEquals("dark glass", 0.77f, d.glass.alpha, 0.006f)
        assertColor("dark glass tint", "#FF201E27", d.glass.copy(alpha = 1f))
        assertColor("dark glass-edge", hex(Color.White.copy(alpha = 0.09f)), d.glassEdge)
        assertColor("dark c-shadow", hex(Color.Black.copy(alpha = 0.5f)), d.shadow)
        assertColor("dark shadow-strong", hex(Color.Black.copy(alpha = 0.65f)), d.shadowStrong)
        assertColor("dark c-scrim", hex(Color.Black.copy(alpha = 0.56f)), d.scrim)
        assertColor("dark sheet", "#FF201E27", d.sheet)
    }

    @Test
    fun `the status tones are the prototype's, light and dark`() {
        val lightTones = mapOf(
            Tone.SYNCED to listOf("#FFD3F3DF", "#FF0B4F30", "#FF1D7A4A"),
            Tone.PLAIN to listOf("#FFF8E9B8", "#FF4F3D00", "#FF856600"),
            Tone.CHECK to listOf("#FFFFDCC2", "#FF5C2A00", "#FFA35200"),
            Tone.NONE to listOf("#FFE7E4ED", "#FF45424E", "#FF696574"),
            Tone.ERROR to listOf("#FFFFDAD3", "#FF6D150B", "#FFB3261E"),
            Tone.AI_SYNCED to listOf("#FFCDEFF1", "#FF084A50", "#FF12767E"),
            Tone.AI_WRITTEN to listOf("#FFF6DAF3", "#FF5A1456", "#FF97338F"),
            Tone.INSTRUMENTAL to listOf("#FFDCE5EE", "#FF293A48", "#FF4B6477"),
        )
        val darkTones = mapOf(
            Tone.SYNCED to listOf("#FF12392A", "#FFA9EBC4", "#FF72D69E"),
            Tone.PLAIN to listOf("#FF3D3105", "#FFF2D47C", "#FFE1BB42"),
            Tone.CHECK to listOf("#FF4A2406", "#FFFFC597", "#FFFF9F58"),
            Tone.NONE to listOf("#FF2D2B35", "#FFCCC7D5", "#FF9E99A9"),
            Tone.ERROR to listOf("#FF4C1711", "#FFFFB4A8", "#FFFF8A7C"),
            Tone.AI_SYNCED to listOf("#FF0F383C", "#FFA4E3E8", "#FF5ECED6"),
            Tone.AI_WRITTEN to listOf("#FF43183E", "#FFF5BBEE", "#FFE68BDB"),
            Tone.INSTRUMENTAL to listOf("#FF21303B", "#FFBCCDDB", "#FF8FA7BB"),
        )
        listOf(LightStatusColors to lightTones, DarkStatusColors to darkTones).forEach { (colors, table) ->
            table.forEach { (tone, want) ->
                val got = colors[tone]
                assertEquals("$tone", want, listOf(hex(got.container), hex(got.onContainer), hex(got.accent)))
            }
        }
    }

    private fun assertRole(name: String, style: TextStyle, size: Float, line: Float, weight: Int, trackingEm: Float?) {
        assertEquals("$name size", size.sp, style.fontSize)
        assertEquals("$name line height", line.sp, style.lineHeight)
        assertEquals("$name weight", FontWeight(weight), style.fontWeight)
        if (trackingEm == null) assertEquals("$name tracking", 0.sp, style.letterSpacing) else assertEquals("$name tracking", trackingEm.em, style.letterSpacing)
        assertNotEquals("$name font", FontFamily.Default, style.fontFamily)
    }

    @Test
    fun `the text roles are the prototype's sizes, weights and tracking`() {
        val t = AppTypography
        assertRole("page title (.topbar h1, .ptitle)", t.headlineSmall, 26f, 32f, 600, -0.015f)
        assertRole("sheet title (.lsheet h2)", t.titleLarge, 22f, 28f, 600, -0.01f)
        assertRole("group heading (.gh b)", t.titleMedium, 17f, 22f, 600, -0.01f)
        assertRole("Now bar title (.nowbar .h)", t.titleSmall, 15f, 20f, 600, null)
        assertRole("row title (.srow .h)", t.bodyLarge, 16f, 22f, 400, null)
        assertRole("row subtitle (.srow .s)", t.bodyMedium, 14f, 20f, 400, null)
        assertRole("small print (.times)", t.bodySmall, 12f, 16f, 400, null)
        assertRole("chip (.chip)", t.labelLarge, 14f, 20f, 500, null)
        assertRole("pill (.pill)", t.labelMedium, 12f, 16f, 500, null)
        assertRole("dock label (.di)", t.labelSmall, 11.5f, 16f, 500, null)
        assertRole("big count (.ring200 .big)", t.displayMedium, 44f, 52f, 600, -0.02f)
    }

    @Test
    fun `body text is Roboto Flex and titles Bricolage, as the prototype loads them`() {
        assertSame(BodyFont, AppTypography.bodyLarge.fontFamily)
        assertSame(BodyFont, AppTypography.labelSmall.fontFamily)
        assertSame(displayFont(26), AppTypography.headlineSmall.fontFamily)
        assertSame(displayFont(17), AppTypography.titleMedium.fontFamily)
    }

    /**
     * The Now bar and the dock draw [ChromeColors.bar] (GlassSurface with `solid = true`): the elevated surface, opaque.
     * At 94% a list's text showed through on the phone, 1.13:1 light and 1.19:1 dark against its own rows' background.
     */
    @Test
    fun `the Now bar and the dock are opaque, so a list's text never shows through, with the theme's own ink on them, light and dark`() {
        val ends = listOf(LightColors to chromeColors(LightColors, LightBrandColors, dark = false), DarkColors to chromeColors(DarkColors, DarkBrandColors, dark = true))
        ends.forEach { (scheme, chrome) ->
            val ink = barInk(scheme)
            assertEquals(scheme.onSurface, ink.title)
            assertEquals(scheme.onSurfaceVariant, ink.line)
            assertColor("bar", hex(scheme.surfaceContainerHigh), chrome.bar)
            assertEquals(1f, chrome.bar.alpha)
            // A list's words under the bar and the page around them come out the same colour: nothing of them is left.
            val rows = listOf(scheme.onSurface, scheme.onSurfaceVariant, scheme.background, Color.White, Color.Black).map { chrome.bar.compositeOver(it) }
            rows.forEach { row -> assertEquals(1f, contrast(row, chrome.bar.compositeOver(scheme.background)), 0.001f) }
            // And the bar's own words keep their contrast on it.
            assertTrue(contrast(ink.title, chrome.bar) >= 4.5f)
            assertTrue(contrast(ink.line, chrome.bar) >= 4.5f)
        }
        // The JVM has no Compose renderer here (no Robolectric), so which fill a composable draws is read from its source:
        // the Now bar and the dock are solid glass, and solid glass draws the bar colour.
        val source = { name: String -> java.io.File("src/main/java/app/lyrify/ui/$name").readText() }
        val nowBar = source("NowBar.kt")
        val chromeKt = source("Chrome.kt")
        assertTrue(Regex("GlassSurface\\(null, CircleShape, [^\\n]*, solid = true\\)").containsMatchIn(nowBar))
        assertTrue(Regex("fun Dock\\([^\\n]*\\n\\s*GlassSurface\\([^\\n]*solid = true\\)").containsMatchIn(chromeKt))
        assertTrue(chromeKt.contains("drawRect(if (solid) chrome.bar else chrome.glassSolid)"))
        // The bar's words take the ink, never the colour left by whatever is around them.
        assertTrue(nowBar.contains("color = ink.title"))
        assertTrue(nowBar.contains("color = ink.line"))
    }

    @Test
    fun `a view's card is drawn behind its content, which still takes the page's ink, so nothing in it is left black on dark`() {
        // Read from the source, as above: the card (the player's included) draws its own fill behind a see-through
        // Surface, so the Surface must still hand its content the page's ink, or every word and icon without a colour of
        // its own (the lyrics' "Aa") falls back to black.
        val source = { name: String -> java.io.File("src/main/java/app/lyrify/ui/$name").readText() }
        assertTrue(Regex("color = Color\\.Transparent,\\s*(//[^\\n]*\\s*)?contentColor = MaterialTheme\\.colorScheme\\.onBackground,").containsMatchIn(source("AppShell.kt")))
        listOf(LightColors, DarkColors).forEach { scheme -> assertTrue(contrast(scheme.onBackground, scheme.background) >= 4.5f) }
        // And "Aa", on the lyrics panel and on full lyrics, is one button in the surface's own ink.
        assertTrue(source("LyricsViews.kt").contains("Text(\"Aa\", style = AaLetters, color = MaterialTheme.colorScheme.onSurface)"))
        assertTrue(source("PlayerScreen.kt").contains("AaButton(enabled)"))
        listOf(LightColors, DarkColors).forEach { scheme -> assertTrue(contrast(scheme.onSurface, scheme.surfaceContainerLow) >= 4.5f) }
    }
    // Theme: System, Light, Dark and Pure black (features 2026-11-15, item 3)

    @Test
    fun `the theme follows the phone, or is light or dark whatever the phone, and Pure black only darkens a dark theme`() {
        fun variant(theme: ThemeChoice, pureBlack: Boolean, phoneDark: Boolean) = themeVariant(DisplaySettings(theme, pureBlack), phoneDark)

        assertEquals(ThemeVariant.LIGHT, variant(ThemeChoice.SYSTEM, false, phoneDark = false))
        assertEquals(ThemeVariant.DARK, variant(ThemeChoice.SYSTEM, false, phoneDark = true))
        assertEquals(ThemeVariant.PURE_BLACK, variant(ThemeChoice.SYSTEM, true, phoneDark = true))
        assertEquals(ThemeVariant.LIGHT, variant(ThemeChoice.SYSTEM, true, phoneDark = false))
        assertEquals(ThemeVariant.LIGHT, variant(ThemeChoice.LIGHT, true, phoneDark = true))
        assertEquals(ThemeVariant.DARK, variant(ThemeChoice.DARK, false, phoneDark = false))
        assertEquals(ThemeVariant.PURE_BLACK, variant(ThemeChoice.DARK, true, phoneDark = false))
        // Light and dark are the prototype's schemes, as before.
        assertSame(LightColors, ThemeVariant.LIGHT.colors)
        assertSame(DarkColors, ThemeVariant.DARK.colors)
        assertEquals(listOf(false, true, true), ThemeVariant.entries.map { it.dark })
    }

    @Test
    fun `the choice is handed to Android as the app's own night mode, so its starting window follows it from Android 12 on`() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, nightModeOf(ThemeChoice.SYSTEM))
        assertEquals(UiModeManager.MODE_NIGHT_NO, nightModeOf(ThemeChoice.LIGHT))
        assertEquals(UiModeManager.MODE_NIGHT_YES, nightModeOf(ThemeChoice.DARK))
    }

    /**
     * No frame in the wrong theme at start: the window behind KJB's first frame is the theme's page colour, light or night,
     * and KJB draws nothing until the stored choice is read, then its first frame in that theme, with the system bars'
     * icons to match. A change of the phone's theme or of the choice reaches the page without the activity being made
     * again (no blink).
     */
    @Test
    fun `the theme is known before the first frame, and the window behind it is the theme's page`() {
        fun color(file: String): String = Regex("""<color name="kjb_window">(#[0-9A-Fa-f]{6,8})</color>""").find(File(file).readText())!!.groupValues[1].uppercase()
        assertEquals(hex(LightColors.background).takeLast(6), color("src/main/res/values/themes.xml").takeLast(6))
        assertEquals(hex(DarkColors.background).takeLast(6), color("src/main/res/values-night/themes.xml").takeLast(6))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val main = manifest.substring(manifest.indexOf("android:name=\".ui.MainActivity\""), manifest.indexOf("</activity>", manifest.indexOf("android:name=\".ui.MainActivity\"")))
        assertTrue(main, "android:theme=\"@style/Theme.Lyrify.Main\"" in main)
        assertTrue(main, "android:configChanges=\"uiMode\"" in main)
        val activity = source("MainActivity.kt")
        val content = activity.substring(activity.indexOf("setContent {"))
        val read = content.indexOf("?: return@setContent")
        assertTrue("MainActivity waits for the stored theme", read > 0 && read < content.indexOf("LyrifyTheme(display"))
        assertTrue(activity.contains("ThemeWindow(variant, chosen)"))
    }

    /**
     * Review F2, finding 3: the window before KJB's first frame is the last theme drawn, Pure black too, on every Android
     * version: a small copy of the choice is kept beside settings.json and read before anything is drawn.
     */
    @Test
    fun `the window before the first frame takes the last theme drawn, Pure black too, from a copy read at once`() {
        assertEquals(DisplaySettings(ThemeChoice.DARK, pureBlack = true), storedDisplay("DARK", pureBlack = true))
        assertEquals(DisplaySettings(ThemeChoice.SYSTEM, pureBlack = true), storedDisplay("SEPIA", pureBlack = true))
        assertEquals(DisplaySettings(), storedDisplay(null, pureBlack = false))
        assertEquals(ThemeVariant.PURE_BLACK, themeVariant(storedDisplay("SYSTEM", pureBlack = true), phoneDark = true))
        val activity = source("MainActivity.kt")
        val create = activity.substring(activity.indexOf("override fun onCreate("))
        assertTrue("the window is painted before setContent", create.indexOf("paintWindow(") in 0 until create.indexOf("setContent {"))
        assertTrue(activity.contains("ThemeCopy.write("))
    }

    /** Review F2, finding 6: a new theme redraws the home-screen widgets, which take the app's night mode when drawn. */
    @Test
    fun `a change of theme redraws the home-screen widgets`() {
        val night = source("MainActivity.kt").substringAfter("private fun NightMode(").substringBefore("\n}\n")
        assertTrue(night.contains("Widgets.refresh(context)"))
    }
}
