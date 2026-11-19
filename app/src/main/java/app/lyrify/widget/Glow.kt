package app.lyrify.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.graphics.scale
import app.lyrify.auto.CarCovers
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The Glow widget's pictures (the design the user chose, 2026-11-19): the song's cover, small and sharp on the left, and
// the same cover blurred and darkened behind the whole widget. Glance draws no blur or scrim, so the background is made
// here, once per song and layout, off the main thread ([Widgets.show]): cut to the layout's shape, scaled down hard,
// blurred, darkened under a brightness white text reads on, and on Android 11, where the widget's corner radius is not
// drawn, its corners cut round. Pixels are ARGB ints, as Bitmap.getPixels gives them; the functions on them are pure.

/** The width of every layout's background, in pixels: small, as a blur needs no more, and the launcher scales it up. */
const val GLOW_WIDTH_PX: Int = 192

/**
 * The width of the widget the backgrounds are shaped for, in dp: four cells on a common phone. A widget of another shape
 * stretches its background a little, which the blur hides.
 */
const val GLOW_SHAPE_WIDTH_DP: Int = 360

/** The widget's corners on Android 11, drawn into the background, in dp (the preview's 24). */
const val GLOW_CORNER_DP: Int = 24

/** How far the blur reaches, in pixels of the [GLOW_WIDTH_PX] background (three passes: about 5% of its width). */
const val GLOW_BLUR_PX: Int = 9

/** What is left of the cover's brightness: about 45% darker. */
const val GLOW_BRIGHTNESS: Float = 0.55f

/** How much more vivid the colours come out, before darkening (the preview's saturate(1.25)). */
const val GLOW_SATURATION: Float = 1.25f

/**
 * The most a background pixel may give off (WCAG 2's relative luminance): white text has a contrast of at least 5 to 1
 * on it, so the title, and the artist at 82% white, read on any cover, a white one too.
 */
const val GLOW_MAX_LUMINANCE: Double = 0.16

/** The sharp cover's largest side, in pixels, whatever the screen's density: under a megabyte for an update in all. */
const val MAX_COVER_PX: Int = 384

/** A bitmap's size in pixels. */
data class PixelSize(val width: Int, val height: Int)

/** A part of a bitmap, in pixels: its left and top edges and its size. */
data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** [layout]'s background, [GLOW_WIDTH_PX] wide, at the shape of its widget ([GlowLayout.shapeHeightDp]). */
fun glowSize(layout: GlowLayout): PixelSize =
    PixelSize(GLOW_WIDTH_PX, (GLOW_WIDTH_PX.toFloat() * layout.shapeHeightDp / GLOW_SHAPE_WIDTH_DP).roundToInt().coerceAtLeast(1))

/** The widget's corner radius in pixels of a background, for Android 11 ([roundCorners]). */
fun glowCornerPx(): Float = GLOW_CORNER_DP.toFloat() * GLOW_WIDTH_PX / GLOW_SHAPE_WIDTH_DP

/** The sharp cover's size in pixels: the large layout's slot on a screen of [density], at most [MAX_COVER_PX]. */
fun coverPx(density: Float): Int = min((GlowLayout.LARGE.coverDp * density).roundToInt(), MAX_COVER_PX)

/** The bitmap memory one widget update carries on a screen of [density]: the three backgrounds and the sharp cover they share. */
fun widgetBitmapBytes(density: Float): Long =
    (GlowLayout.entries.sumOf { glowSize(it).let { size -> size.width.toLong() * size.height } } + coverPx(density).toLong() * coverPx(density)) * 4

/** The largest part of a [width] by [height] picture with the shape [aspect] (width over height), from its middle. */
fun centerCrop(width: Int, height: Int, aspect: Float): CropRect {
    val wide = width.toFloat() / height > aspect
    val w = if (wide) (height * aspect).roundToInt().coerceIn(1, width) else width
    val h = if (wide) height else (width / aspect).roundToInt().coerceIn(1, height)
    return CropRect((width - w) / 2, (height - h) / 2, w, h)
}

/**
 * [pixels] ([width] by [height]) blurred: three passes of a box [radius] pixels each way, across then down, near a
 * Gaussian blur. Past the edges each row and column goes on in its edge's colour, so the edges do not darken. Each
 * channel is kept as a fraction until the end, so the light is neither lost nor gained. A new array.
 */
fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
    if (radius <= 0) return pixels.copyOf()
    val channels = Array(4) { shift -> FloatArray(pixels.size) { ((pixels[it] ushr (shift * 8)) and 0xFF).toFloat() } }
    val line = FloatArray(max(width, height))
    repeat(BLUR_PASSES) {
        for (channel in channels) {
            for (y in 0 until height) blurLine(channel, y * width, 1, width, radius, line)
            for (x in 0 until width) blurLine(channel, x, width, height, radius, line)
        }
    }
    return IntArray(pixels.size) { i ->
        (0 until 4).fold(0) { pixel, shift -> pixel or (channels[shift][i].roundToInt().coerceIn(0, 255) shl (shift * 8)) }
    }
}

/** One line of [values] ([count] of them from [start], [step] apart) averaged over a box of [radius] each way, in place. */
private fun blurLine(values: FloatArray, start: Int, step: Int, count: Int, radius: Int, line: FloatArray) {
    for (i in 0 until count) line[i] = values[start + i * step]
    val box = 2 * radius + 1
    var sum = 0f
    for (k in -radius..radius) sum += line[k.coerceIn(0, count - 1)]
    for (i in 0 until count) {
        values[start + i * step] = sum / box
        sum += line[(i + radius + 1).coerceAtMost(count - 1)] - line[(i - radius).coerceAtLeast(0)]
    }
}

/**
 * One pixel of the glow: [GLOW_SATURATION] times as vivid, [GLOW_BRIGHTNESS] as bright, and dimmed further when it would
 * still give off more than [GLOW_MAX_LUMINANCE]. Its alpha is kept.
 */
fun glowColor(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
    fun vivid(channel: Int): Float = ((luma + (channel - luma) * GLOW_SATURATION) * GLOW_BRIGHTNESS).coerceIn(0f, 255f)
    val rgb = floatArrayOf(vivid(r), vivid(g), vivid(b))
    val lit = 0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2])
    val out = if (lit <= GLOW_MAX_LUMINANCE) rgb else (GLOW_MAX_LUMINANCE / lit).let { k -> FloatArray(3) { srgb(linear(rgb[it]) * k) } }
    return (argb and ALPHA) or (out[0].roundToInt() shl 16) or (out[1].roundToInt() shl 8) or out[2].roundToInt()
}

/** An sRGB channel (0 to 255) as linear light (0 to 1). */
private fun linear(channel: Float): Double = (channel / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

/** Linear light (0 to 1) as an sRGB channel (0 to 255). */
private fun srgb(light: Double): Float =
    ((if (light <= 0.0031308) light * 12.92 else 1.055 * light.pow(1 / 2.4) - 0.055) * 255).toFloat().coerceIn(0f, 255f)

/**
 * [pixels] ([width] by [height]) with their four corners cut round at [radius] pixels: outside the arc see-through, along
 * it faded over about a pixel, the colours kept. A new array; a [radius] of 0 is a copy.
 */
fun roundCorners(pixels: IntArray, width: Int, height: Int, radius: Float): IntArray {
    val out = pixels.copyOf()
    if (radius <= 0f) return out
    for (y in 0 until height) for (x in 0 until width) {
        // The pixel's centre, from the centre of its corner's arc.
        val dx = max(radius - (x + 0.5f), (x + 0.5f) - (width - radius))
        val dy = max(radius - (y + 0.5f), (y + 0.5f) - (height - radius))
        if (dx <= 0f || dy <= 0f) continue
        val cover = (radius - sqrt(dx * dx + dy * dy) + 0.5f).coerceIn(0f, 1f)
        val i = y * width + x
        val alpha = ((pixels[i] ushr 24) * cover).roundToInt()
        out[i] = (pixels[i] and ALPHA.inv()) or (alpha shl 24)
    }
    return out
}

/** The glow from a cover's [pixels] already cut and scaled to the background's size: blurred, darkened, round at [cornerPx] when above 0. */
fun glowPixels(pixels: IntArray, width: Int, height: Int, cornerPx: Float): IntArray {
    val blurred = boxBlur(pixels, width, height, GLOW_BLUR_PX)
    val glow = IntArray(blurred.size) { glowColor(blurred[it]) }
    return if (cornerPx > 0f) roundCorners(glow, width, height, cornerPx) else glow
}

private const val BLUR_PASSES = 3
private const val ALPHA = 0xFF shl 24

// The bitmaps

/**
 * Android 12 and later clip a widget to the home screen's corner radius (GlanceModifier.cornerRadius); on Android 11 it
 * is not drawn, so the pictures carry their own corners.
 */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
internal val homeRoundsCorners: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** [cover]'s glow behind [layout]: cut to its shape from the middle, scaled to [glowSize], then [glowPixels]. Blocking. */
internal fun glowBitmap(cover: Bitmap, layout: GlowLayout): Bitmap {
    val size = glowSize(layout)
    val crop = centerCrop(cover.width, cover.height, size.width.toFloat() / size.height)
    val small = Bitmap.createBitmap(software(cover), crop.x, crop.y, crop.width, crop.height).scale(size.width, size.height)
    val pixels = IntArray(size.width * size.height).also { small.getPixels(it, 0, size.width, 0, 0, size.width, size.height) }
    val glow = glowPixels(pixels, size.width, size.height, if (homeRoundsCorners) 0f else glowCornerPx())
    return Bitmap.createBitmap(glow, size.width, size.height, Bitmap.Config.ARGB_8888)
}

/** [cover] square from its middle at [px], its corners cut round on Android 11 as the large layout's are. Blocking. */
internal fun sharpCover(cover: Bitmap, px: Int): Bitmap {
    val crop = centerCrop(cover.width, cover.height, 1f)
    val square = Bitmap.createBitmap(software(cover), crop.x, crop.y, crop.width, crop.height).let { if (it.width > px) it.scale(px, px) else it }
    if (homeRoundsCorners) return square
    val side = square.width
    val pixels = IntArray(side * side).also { square.getPixels(it, 0, side, 0, 0, side, side) }
    val radius = side.toFloat() * GlowLayout.LARGE.coverCornerDp / GlowLayout.LARGE.coverDp
    return Bitmap.createBitmap(roundCorners(pixels, side, side, radius), side, side, Bitmap.Config.ARGB_8888)
}

/** The glows of KJB's default cover, for a song without one; made at the first call, then kept for the process. Blocking. */
internal fun defaultGlow(context: Context): Map<GlowLayout, Bitmap> = madeDefault ?: run {
    val cover = CarCovers.defaultBitmap(context)
    GlowLayout.entries.associateWith { glowBitmap(cover, it) }
}.also { madeDefault = it }

@Volatile
private var madeDefault: Map<GlowLayout, Bitmap>? = null

/** [bitmap] in memory the CPU can read: a hardware bitmap is copied. */
private fun software(bitmap: Bitmap): Bitmap = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
