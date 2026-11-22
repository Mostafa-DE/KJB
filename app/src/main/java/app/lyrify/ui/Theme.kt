package app.lyrify.ui

import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lyrify.R
import app.lyrify.settings.DisplaySettings
import app.lyrify.settings.ThemeChoice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** The launcher icon's true black: the brand mark's tile and the onboarding hero, in both themes. */
val BrandBlack: Color = Color(0xFF0A0A0B)

/** The hairline that keeps the black tile visible on a dark background, as the launcher icon's lifted edge does. */
val BrandBlackBorder: Color = Color(0xFF3A3A3F)

/** Deep ink violet: the toast, and the light theme's action colour. */
val BrandInk: Color = Color(0xFF2E2466)

/** Lilac: text on the black hero and on the toast. */
val BrandLilac: Color = Color(0xFFF4F1FF)

/** The hairline that keeps the toast visible on a dark background. */
val BrandInkBorder: Color = Color(0xFF5A50AE)

/** The coral of the K's note: a toast's action. */
val LauncherCoral: Color = Color(0xFFFF8A70)

/** Side margin of every screen (M3 compact window margin). */
val ScreenPadding = 16.dp

/**
 * The KJB palette, the prototype's tokens, on every phone: ink as primary, coral as tertiary. Fixed rather than Material
 * You's wallpaper colours (dropped in 3.0), so the app looks the same on every phone and on every wallpaper.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF4F44A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF1A0F5C),
    inversePrimary = Color(0xFFC8BFFF),
    secondary = Color(0xFF5E5A71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4DFF9),
    onSecondaryContainer = Color(0xFF1C1832),
    tertiary = Color(0xFFA2402A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3B0A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1A23),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1A23),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FC),
    surfaceContainer = Color(0xFFF1ECF7),
    surfaceContainerHigh = Color(0xFFEBE6F2),
    surfaceContainerHighest = Color(0xFFE5E0EC),
    surfaceDim = Color(0xFFDDD8E3),
    surfaceBright = Color(0xFFFCF8FF),
    outline = Color(0xFF7A7581),
    outlineVariant = Color(0xFFCBC4D2),
    inverseSurface = Color(0xFF312F38),
    inverseOnSurface = Color(0xFFF4EFF9),
    surfaceTint = Color(0xFF4F44A6),
    scrim = Color(0xFF000000),
    // The fixed roles: the same in light and dark, the palette's tones 90, 80, 10 and 30 of each hue.
    primaryFixed = Color(0xFFE5DEFF),
    primaryFixedDim = Color(0xFFC8BFFF),
    onPrimaryFixed = Color(0xFF1A0F5C),
    onPrimaryFixedVariant = Color(0xFF3F3590),
    secondaryFixed = Color(0xFFE4DFF9),
    secondaryFixedDim = Color(0xFFC8C3DC),
    onSecondaryFixed = Color(0xFF1C1832),
    onSecondaryFixedVariant = Color(0xFF474359),
    tertiaryFixed = Color(0xFFFFDBD1),
    tertiaryFixedDim = Color(0xFFFFB4A1),
    onTertiaryFixed = Color(0xFF3B0A00),
    onTertiaryFixedVariant = Color(0xFF7F2B17),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFC8BFFF),
    onPrimary = Color(0xFF2E2466),
    primaryContainer = Color(0xFF3F3590),
    onPrimaryContainer = Color(0xFFE5DEFF),
    inversePrimary = Color(0xFF4F44A6),
    secondary = Color(0xFFC8C3DC),
    onSecondary = Color(0xFF312D42),
    secondaryContainer = Color(0xFF474359),
    onSecondaryContainer = Color(0xFFE4DFF9),
    tertiary = Color(0xFFFFB4A1),
    onTertiary = Color(0xFF5F1605),
    tertiaryContainer = Color(0xFF7F2B17),
    onTertiaryContainer = Color(0xFFFFDBD1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF13121A),
    onBackground = Color(0xFFE6E1EC),
    surface = Color(0xFF13121A),
    onSurface = Color(0xFFE6E1EC),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0E0D14),
    surfaceContainerLow = Color(0xFF1B1A22),
    surfaceContainer = Color(0xFF201E27),
    surfaceContainerHigh = Color(0xFF2A2832),
    surfaceContainerHighest = Color(0xFF35333D),
    surfaceDim = Color(0xFF13121A),
    surfaceBright = Color(0xFF3A3841),
    outline = Color(0xFF948F9A),
    outlineVariant = Color(0xFF49454F),
    inverseSurface = Color(0xFFE6E1EC),
    inverseOnSurface = Color(0xFF312F38),
    surfaceTint = Color(0xFFC8BFFF),
    scrim = Color(0xFF000000),
    // The fixed roles: the same in light and dark, the palette's tones 90, 80, 10 and 30 of each hue.
    primaryFixed = Color(0xFFE5DEFF),
    primaryFixedDim = Color(0xFFC8BFFF),
    onPrimaryFixed = Color(0xFF1A0F5C),
    onPrimaryFixedVariant = Color(0xFF3F3590),
    secondaryFixed = Color(0xFFE4DFF9),
    secondaryFixedDim = Color(0xFFC8C3DC),
    onSecondaryFixed = Color(0xFF1C1832),
    onSecondaryFixedVariant = Color(0xFF474359),
    tertiaryFixed = Color(0xFFFFDBD1),
    tertiaryFixedDim = Color(0xFFFFB4A1),
    onTertiaryFixed = Color(0xFF3B0A00),
    onTertiaryFixedVariant = Color(0xFF7F2B17),
)

/**
 * Pure black (features 2026-11-15, item 3): the dark scheme with its pages black, for OLED screens. Cards, sheets and
 * the bars keep their lift above the page, so they still show; every ink stays the dark theme's (ContrastTest).
 */
internal val PureBlackColors: ColorScheme = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0E0D14),
)

/** A status tone's colours: a pill is [container] with [onContainer]; [accent] marks plain surfaces (bars, dots, icons). */
@Immutable
data class ToneColors(val container: Color, val onContainer: Color, val accent: Color)

/**
 * The status tones: each comes with its own container.
 * On-container text is 7:1 or more on its container, accents 3:1 or more on the container and on the surface.
 */
@Immutable
class StatusColors(private val tones: Map<Tone, ToneColors>) {
    operator fun get(tone: Tone): ToneColors = tones.getValue(tone)
}

private fun tone(container: Long, onContainer: Long, accent: Long) = ToneColors(Color(container), Color(onContainer), Color(accent))

internal val LightStatusColors = StatusColors(
    mapOf(
        Tone.SYNCED to tone(0xFFD3F3DF, 0xFF0B4F30, 0xFF1D7A4A),
        Tone.AI_SYNCED to tone(0xFFCDEFF1, 0xFF084A50, 0xFF12767E),
        Tone.AI_WRITTEN to tone(0xFFF6DAF3, 0xFF5A1456, 0xFF97338F),
        Tone.PLAIN to tone(0xFFF8E9B8, 0xFF4F3D00, 0xFF856600),
        Tone.CHECK to tone(0xFFFFDCC2, 0xFF5C2A00, 0xFFA35200),
        Tone.NONE to tone(0xFFE7E4ED, 0xFF45424E, 0xFF696574),
        Tone.INSTRUMENTAL to tone(0xFFDCE5EE, 0xFF293A48, 0xFF4B6477),
        Tone.ERROR to tone(0xFFFFDAD3, 0xFF6D150B, 0xFFB3261E),
    ),
)

internal val DarkStatusColors = StatusColors(
    mapOf(
        Tone.SYNCED to tone(0xFF12392A, 0xFFA9EBC4, 0xFF72D69E),
        Tone.AI_SYNCED to tone(0xFF0F383C, 0xFFA4E3E8, 0xFF5ECED6),
        Tone.AI_WRITTEN to tone(0xFF43183E, 0xFFF5BBEE, 0xFFE68BDB),
        Tone.PLAIN to tone(0xFF3D3105, 0xFFF2D47C, 0xFFE1BB42),
        Tone.CHECK to tone(0xFF4A2406, 0xFFFFC597, 0xFFFF9F58),
        Tone.NONE to tone(0xFF2D2B35, 0xFFCCC7D5, 0xFF9E99A9),
        Tone.INSTRUMENTAL to tone(0xFF21303B, 0xFFBCCDDB, 0xFF8FA7BB),
        Tone.ERROR to tone(0xFF4C1711, 0xFFFFB4A8, 0xFFFF8A7C),
    ),
)

/** The status tones of the active theme, picked by the same dark flag as the colour scheme. */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Whether the active theme is dark (the brand tile gets a border there). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * KJB's accent on top of either scheme: [highlight] is the "sung line" coral (the glyph's sung part, the queue bar, the
 * AI ring, the active filter's underline); [highlightContainer] is for brand surfaces only, never a status. [suggestion]
 * is suggestions' teal (the approved suggestions mockup's pick colour): Smart shuffle's button, apart from Shuffle's coral.
 */
@Immutable
data class BrandColors(val highlight: Color, val onHighlight: Color, val highlightContainer: Color, val onHighlightContainer: Color, val suggestion: Color)

internal val LightBrandColors = BrandColors(Color(0xFFC9472C), Color(0xFFFFFFFF), Color(0xFFFFDBD1), Color(0xFF3B0A00), Color(0xFF0B6B62))
internal val DarkBrandColors = BrandColors(Color(0xFFFF9A80), Color(0xFF4A1205), Color(0xFF7F2B17), Color(0xFFFFDBD1), Color(0xFF7FD6CA))

val LocalBrandColors = staticCompositionLocalOf { LightBrandColors }

/** The weights the prototype sets: regular, medium, semibold, its 650 for a selected tab or a lyric, and bold. */
private val WEIGHTS = listOf(400, 500, 600, 650, 700)

/**
 * Roboto Flex (SIL OFL 1.1), cut to its weight and optical size axes: the body text, as the prototype sets it, at its
 * default optical size (14, the body size).
 */
val BodyFont: FontFamily = FontFamily(
    WEIGHTS.map { Font(R.font.roboto_flex, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it))) },
)

private val displayFamilies = HashMap<Int, FontFamily>()

/**
 * Bricolage Grotesque (SIL OFL 1.1) drawn for [size] sp: its optical size follows the text size, as a browser's
 * font-optical-sizing does, so a 44 sp count is tighter than a 17 sp heading. Titles, big numbers and lyrics.
 */
fun displayFont(size: Int): FontFamily = displayFamilies.getOrPut(size) {
    FontFamily(
        WEIGHTS.map {
            Font(R.font.bricolage_grotesque, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it), FontVariation.Setting("opsz", size.toFloat())))
        },
    )
}

/** CSS's line box: the extra height of a line split evenly above and below the text. */
private val CssLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** A display style: Bricolage at [size]/[lineHeight] sp, [weight], tracked by [tracking] em. */
fun display(size: Int, lineHeight: Int, tracking: Double = 0.0, weight: Int = 600): TextStyle = TextStyle(
    fontFamily = displayFont(size),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em,
    lineHeightStyle = CssLine,
)

/** A body style: Roboto Flex at [size]/[lineHeight] sp, [weight], untracked as the prototype's text is. */
fun body(size: Float, lineHeight: Int, weight: Int = 400): TextStyle = TextStyle(
    fontFamily = BodyFont,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
    lineHeightStyle = CssLine,
)

/**
 * The prototype's text roles. Bricolage: big numbers (display), page and player titles (headline), sheet and dialog
 * titles (titleLarge), group headings (titleMedium). Roboto Flex: the Now bar's title (titleSmall), row titles
 * (bodyLarge), subtitles and sentences (bodyMedium), small print (bodySmall), chips (labelLarge), pills (labelMedium)
 * and the dock (labelSmall).
 */
internal val AppTypography: Typography = Typography(
    displayLarge = display(57, 64, -0.02),
    displayMedium = display(44, 52, -0.02),
    displaySmall = display(36, 44, -0.02),
    headlineLarge = display(32, 40, -0.015),
    headlineMedium = display(28, 36, -0.015),
    headlineSmall = display(26, 32, -0.015),
    titleLarge = display(22, 28, -0.01),
    titleMedium = display(17, 22, -0.01),
    titleSmall = body(15f, 20, 600),
    bodyLarge = body(16f, 22),
    bodyMedium = body(14f, 20),
    bodySmall = body(12f, 16),
    labelLarge = body(14f, 20, 500),
    labelMedium = body(12f, 16, 500),
    labelSmall = body(11.5f, 16, 500),
)

/** Counts line up: tabular figures. */
const val TABULAR: String = "tnum"

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The scheme for a light or [dark] phone: the prototype's, the same on every Android version and wallpaper. */
fun appColors(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

/** The themes KJB draws: each one's [colors], status tones and brand colours, and whether it is [dark]. */
enum class ThemeVariant(val colors: ColorScheme, val status: StatusColors, val brand: BrandColors, val dark: Boolean) {
    LIGHT(LightColors, LightStatusColors, LightBrandColors, dark = false),
    DARK(DarkColors, DarkStatusColors, DarkBrandColors, dark = true),
    PURE_BLACK(PureBlackColors, DarkStatusColors, DarkBrandColors, dark = true),
}

/**
 * The theme for [display] (Settings > Display) on a phone whose own theme is dark or not ([phoneDark]): System follows
 * the phone, Light and Dark do not; Pure black turns a dark theme's pages black and leaves a light one as it is.
 */
fun themeVariant(display: DisplaySettings, phoneDark: Boolean): ThemeVariant {
    val dark = when (display.theme) {
        ThemeChoice.SYSTEM -> phoneDark
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    return when {
        !dark -> ThemeVariant.LIGHT
        display.pureBlack -> ThemeVariant.PURE_BLACK
        else -> ThemeVariant.DARK
    }
}

/**
 * [choice] as Android's own night mode for KJB (UiModeManager.setApplicationNightMode, Android 12 and later), which Android
 * keeps and applies before KJB starts, its starting window too. Auto follows the phone.
 */
fun nightModeOf(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
    ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
}

/**
 * The KJB palette (the prototype's tokens), light, dark or Pure black as [display] and the phone say ([themeVariant]).
 * KJB's chrome on top: its colours, its press feedback in place of the ripple, reduced motion and the glass switch, read
 * again each time the app comes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyrifyTheme(display: DisplaySettings = DisplaySettings(), content: @Composable () -> Unit) {
    val variant = themeVariant(display, isSystemInDarkTheme())
    val dark = variant.dark
    val context = LocalContext.current
    val colors = variant.colors
    val status = variant.status
    val brandColors = variant.brand
    var reduced by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    var glass by remember { mutableStateOf(glassOn(context)) }
    LifecycleResumeEffect(context) {
        reduced = !ValueAnimator.areAnimatorsEnabled()
        glass = glassOn(context)
        onPauseOrDispose { }
    }
    CompositionLocalProvider(
        LocalStatusColors provides status,
        LocalBrandColors provides brandColors,
        LocalDarkTheme provides dark,
    ) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography) {
            CompositionLocalProvider(
                // Every plain clickable, toggleable and selectable; no ripple in the Material parts that stay.
                LocalIndication provides PressIndication(),
                // Material's theme gives no content colour: a word or icon outside a Surface (a dialog opened beside the
                // page) would be drawn black, unreadable in dark. It takes the page's ink instead.
                LocalContentColor provides colors.onBackground,
                LocalRippleConfiguration provides null,
                LocalChromeColors provides remember(colors, brandColors, dark) { chromeColors(colors, brandColors, dark) },
                LocalReducedMotion provides reduced,
                LocalGlassBlur provides glass,
                content = content,
            )
        }
    }
}

// The chrome

/**
 * KJB's chrome on either scheme: ink [action] for primary buttons, the ink [toast] with a coral [toastAction], the
 * frosted [glass] of the top bar and the other floating bars (as opaque as it must be for its text over any content,
 * [glassAlpha]; [glassSolid] when nothing blurs under it), the opaque [topBar] under every top bar and the status bar,
 * the opaque [bar] of the Now bar and the dock (a list scrolling behind never shows through), the soft [shadow] of
 * floating chrome, the deeper [shadowStrong] under covers and the Play button, the brand-tinted [scrim] behind sheets and
 * dialogs, and their [sheet] card.
 */
@Immutable
data class ChromeColors(
    val action: Color,
    val onAction: Color,
    val toast: Color,
    val onToast: Color,
    val toastAction: Color,
    val toastEdge: Color,
    val glass: Color,
    val glassSolid: Color,
    val glassEdge: Color,
    val shadow: Color,
    val shadowStrong: Color,
    val scrim: Color,
    val sheet: Color,
    val topBar: Color,
    val bar: Color,
)

internal fun chromeColors(scheme: ColorScheme, brand: BrandColors, dark: Boolean) = ChromeColors(
    action = if (dark) Color(0xFFE5DEFF) else BrandInk,
    onAction = if (dark) Color(0xFF1A0F5C) else BrandLilac,
    toast = BrandInk,
    onToast = BrandLilac,
    toastAction = LauncherCoral,
    toastEdge = if (dark) BrandInkBorder else Color.Transparent,
    // The worst content under the glass: white under dark glass, black under light glass.
    glass = scheme.surfaceContainer.copy(alpha = glassAlpha(scheme.surfaceContainer, scheme.onSurfaceVariant, brand.highlight, if (dark) Color.White else Color.Black)),
    glassSolid = scheme.surfaceContainer.copy(alpha = GLASS_SOLID_ALPHA),
    glassEdge = Color.White.copy(alpha = if (dark) 0.09f else 0.6f),
    shadow = if (dark) Color.Black.copy(alpha = 0.5f) else ShadowInk.copy(alpha = 0.18f),
    shadowStrong = if (dark) Color.Black.copy(alpha = 0.65f) else ShadowInk.copy(alpha = 0.38f),
    scrim = if (dark) Color.Black.copy(alpha = 0.56f) else ShadowInk.copy(alpha = 0.36f),
    sheet = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest,
    // Opaque, the page's own colour: a list scrolls under the top bar and the status bar, never through them, on a page
    // whose bar is never told that content is under it (Settings, phone session 10) as on any other.
    topBar = scheme.background,
    // Opaque: at 94% a list's text read through the Now bar and the dock as it scrolled behind them.
    bar = scheme.surfaceContainerHigh,
)

/** The words on the Now bar and the dock: the title in the theme's ink, the second line in its secondary ink, light or dark. */
@Immutable
data class BarInk(val title: Color, val line: Color)

fun barInk(scheme: ColorScheme): BarInk = BarInk(title = scheme.onSurface, line = scheme.onSurfaceVariant)

val LocalChromeColors = staticCompositionLocalOf { chromeColors(LightColors, LightBrandColors, dark = false) }

/** The contrast ratio of two opaque colours, as WCAG counts it: 1 to 21. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * How opaque frosted [glass] has to be: at least the spec's 72%, and enough that the muted [text] on it keeps 4.5:1 and
 * the coral [mark] (the selected tab, the sung line) 3:1 even with [behind], the worst content, blurred under it.
 */
fun glassAlpha(glass: Color, text: Color, mark: Color, behind: Color): Float {
    var alpha = GLASS_ALPHA
    while (alpha < 1f) {
        val tint = glass.copy(alpha = alpha).compositeOver(behind)
        if (contrast(text, tint) >= MIN_TEXT_CONTRAST && contrast(mark, tint) >= MIN_MARK_CONTRAST) return alpha
        alpha += GLASS_STEP
    }
    return 1f
}

/** Whether animations are off (Remove animations, animator scale 0, Battery Saver): springs snap, slides become fades. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Whether glass chrome blurs what is under it; else it is nearly opaque. */
val LocalGlassBlur = staticCompositionLocalOf { false }

/** Blur needs Android 12; it is off in Battery Saver and when the user raised contrast (Android 14 and later). */
fun glassBlurs(sdk: Int, powerSave: Boolean, contrast: Float): Boolean = sdk >= Build.VERSION_CODES.S && !powerSave && contrast <= 0f

private fun glassOn(context: Context): Boolean {
    val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    val contrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f else 0f
    return glassBlurs(Build.VERSION.SDK_INT, powerSave, contrast)
}

/** The sung line, KJB's one "selected" mark: the dock, the segmented control and the Songs status bar draw it. */
internal val SUNG_LINE_WIDTH = 18.dp
internal val SUNG_LINE_HEIGHT = 3.dp

// Motion

/**
 * KJB's motion tokens: springs, and the durations and curves of the prototype's CSS transitions
 * (player-design/lyrify-prototype.html), with Material 3's standard and emphasized curves where it gives none. Every
 * screen takes its motion from here; with reduced motion each one is snap() or a short fade ([motion]).
 */
object Motion {
    val press: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    /** A small overshoot: the sung line lands. */
    val indicator: SpringSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 500f)

    /** Toasts, menus, dialogs. */
    val snappy: SpringSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 600f)

    /** Sizes and content that moves. */
    val settle: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 300f)

    /** Something the finger let go of short of its goal (the player dragged down, a back swipe given up): it settles back. */
    val release: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 400f)

    /**
     * The player closing into the Now bar, whatever let it go (a finger, the chevron, Back), and a drag on it let go short
     * of closing: critically damped, so it never overshoots the bar. From rest it is half way at about 85 ms and within
     * 1% of the way at about 330 ms; a finger's speed carried in shapes its start, hardly its length (phone test
     * 2026-11-17: a 300 ms tween with all its move in the first 3 to 6 frames read as a cut).
     */
    fun <T> close(visibilityThreshold: T? = null): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = visibilityThreshold)

    // Curves

    /** CSS "ease": the prototype's default (the cover springing back, a queue row moving). */
    val Ease: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Material 3 standard: things that move within the screen. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Material 3 emphasized decelerate: things that arrive. */
    val EmphasizedIn: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material 3 emphasized accelerate: things that leave. */
    val EmphasizedOut: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** The prototype's arrivals with a little overshoot: toastIn, menuIn, dlgIn. */
    val Pop: Easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.1f)

    /** The prototype's sheets and the player rising: sheetIn, upIn. */
    val Rise: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** The cover turning to its lyrics. */
    val Flip: Easing = CubicBezierEasing(0.3f, 0.7f, 0.15f, 1f)

    /** The equalizer's knobs gliding to a preset: ease-out cubic. */
    val Glide: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    // Durations, in milliseconds

    /** A press, a chip's fill, a knob growing under the finger. */
    const val QUICK_MS = 120

    /** A top bar's glass band, a menu. */
    const val SHORT_MS = 180

    /** The scrim behind a sheet; the cover springing back or finishing a swipe; an off section dimming. */
    const val SETTLE_MS = 200

    /** A dialog. */
    const val DIALOG_MS = 220

    /** A toast or a sheet arriving. */
    const val ARRIVE_MS = 280

    /** The player opening; a lyric line taking its colour; a page or a full screen arriving. */
    const val OPEN_MS = 300

    /** The cover turning to its lyrics: the prototype's 550 ms cut to One UI's longest, 500 (decision D3). */
    const val FLIP_MS = 500

    /** A dialog, a state or new text fading in. */
    const val FADE_IN_MS = 150

    /** What leaves as something else fades in: a dialog closing, old text before the new. */
    const val FADE_OUT_MS = 90

    /** The Now bar growing into the player, and back: the largest move, so the longest. */
    const val CONTAINER_MS = 450

    /** With reduced motion, every slide, scale and container transform is a fade this long (decision D4). */
    const val REDUCED_MS = 100
}

/** [spec], or with reduced motion snap(): the value lands at once. */
fun <T> motion(spec: FiniteAnimationSpec<T>, reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else spec

/** How far a press squashes: [scale], or not at all with reduced motion (the tint still shows). */
fun pressScale(scale: Float, reduced: Boolean): Float = if (reduced) 1f else scale

/**
 * KJB's press feedback in place of the ripple: the pressed thing squashes a little toward its centre and takes a soft
 * tint of the content colour over [shape], or of [tint], the fill's own ink, on a filled control (on the dark theme's
 * light ink button the page's ink would not show). With [scale] 1 (rows, and always with reduced motion) only the tint shows.
 * Put the clickable before the background, so the fill squashes with the content.
 */
class PressIndication(private val shape: Shape = RectangleShape, private val scale: Float = 1f, private val tint: Color = Color.Unspecified) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, shape, scale, tint)

    override fun equals(other: Any?): Boolean = other is PressIndication && other.shape == shape && other.scale == scale && other.tint == tint

    override fun hashCode(): Int = 31 * (31 * shape.hashCode() + scale.hashCode()) + tint.hashCode()
}

private class PressNode(private val source: InteractionSource, private val shape: Shape, private val pressedScale: Float, private val tint: Color) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val pressed = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed.animateTo(1f, spring(stiffness = Spring.StiffnessHigh))
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        // A quick tap still shows: reach half way before letting go.
                        if (pressed.value < 0.5f) pressed.animateTo(0.5f, tween(PRESS_MIN_MS))
                        pressed.animateTo(0f, Motion.press)
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        val squash = pressScale(pressedScale, currentValueOf(LocalReducedMotion))
        scale(1f - (1f - squash) * p) {
            this@draw.drawContent()
            if (p > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), tint.takeOrElse { currentValueOf(LocalContentColor) }.copy(alpha = PRESS_TINT * p))
        }
    }
}

private val ShadowInk = Color(0xFF1A143C)
private const val GLASS_ALPHA = 0.72f
private const val GLASS_STEP = 0.01f
private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_MARK_CONTRAST = 3f
private const val GLASS_SOLID_ALPHA = 0.97f

private const val PRESS_TINT = 0.10f
private const val PRESS_MIN_MS = 60
