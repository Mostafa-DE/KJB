package app.lyrify.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The theme equals the prototype's design tokens (player-design/lyrify-prototype.html, lines 6 to 119; the table in
 * handoff/design-fidelity.md): every colour, light and dark, on every Android version, and every text role.
 */
class ThemeTokensTest {
    private fun hex(color: Color): String = String.format("#%08X", color.toArgb())

    private fun assertColor(name: String, expected: String, actual: Color) = assertEquals(name, expected, hex(actual))

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
}
