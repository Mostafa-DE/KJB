package app.lyrify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Every `res/drawable/ic_*.xml` vector (the debug build's too) draws something that shows. A path in another coordinate
 * system than its viewport (24 units in a 960 viewport, or Material Symbols' y from -960 to 0
 * left untranslated) builds fine and draws a speck or nothing at all. The launcher icon, the in-app mark and the
 * notification icon are the Blade K, exactly as its source SVGs draw it.
 */
class VectorIconsTest {
    /** Unit tests run in the module's folder. */
    private val drawables = File("src/main/res/drawable")
    private val debugDrawables = File("src/debug/res/drawable")

    private val vectors: List<Pair<String, Element>> by lazy {
        listOf("" to drawables, "debug/" to debugDrawables).flatMap { (prefix, folder) ->
            val files = folder.listFiles { file -> file.name.startsWith("ic_") && file.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
            files.map { prefix + it.name to parse(it) }.filter { it.second.tagName == "vector" }
        }
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    @Test
    fun `the icons are found`() {
        assertTrue("no vectors in ${drawables.absolutePath}", vectors.size >= 50)
        assertTrue(vectors.any { it.first == "ic_auto_awesome.xml" })
    }

    @Test
    fun `every icon has a size, a viewport and paths with a colour`() {
        val problems = vectors.flatMap { (name, vector) ->
            val tinted = vector.attr("tint") != null
            listOfNotNull(
                "$name: no width or height".takeIf { vector.attr("width") == null || vector.attr("height") == null },
                "$name: no viewport".takeIf { viewport(vector) == null },
                "$name: no path".takeIf { paths(vector).isEmpty() },
            ) + paths(vector).flatMap { (path, _) ->
                listOfNotNull(
                    "$name: a path without pathData".takeIf { path.attr("pathData").isNullOrBlank() },
                    "$name: a path with no fillColor, strokeColor or tint".takeIf {
                        !tinted && path.attr("fillColor") == null && path.attr("strokeColor") == null && gradient(path) == null
                    },
                )
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `every path lies in its viewport, and each icon spans at least a quarter of it`() {
        val problems = vectors.flatMap { (name, vector) ->
            val viewport = viewport(vector) ?: return@flatMap listOf("$name: no viewport")
            val boxes = paths(vector).map { (path, offset) -> bounds(path.attr("pathData").orEmpty())?.shifted(offset) }
            val drawn = boxes.filterNotNull().reduceOrNull(Box::plus)
            listOfNotNull(
                "$name: a path with no points".takeIf { boxes.any { it == null } },
                "$name: a path not inside the viewport $viewport".takeIf { boxes.any { it != null && it !in viewport } },
                "$name: draws $drawn, under a quarter of the viewport $viewport".takeIf {
                    drawn != null && max(drawn.width / viewport.width, drawn.height / viewport.height) < 0.25f
                },
            )
        }
        assertEquals(emptyList<String>(), problems)
    }

    // The Blade K

    /** [file]'s shapes as drawn: each path's data, with the translation of the groups around it. */
    private fun shapes(file: File): List<Pair<String, Pair<Float, Float>>> = paths(parse(file)).map { (path, offset) -> path.attr("pathData").orEmpty() to offset }

    /**
     * Whether every corner of the box around each of [shapes] lies within the adaptive icon's 66 dp safe circle (108-unit
     * canvas), [margin] inside it (half a stroke's width).
     */
    private fun inSafeCircle(shapes: List<Pair<String, Pair<Float, Float>>>, margin: Float = 0f): Boolean = shapes.all { (data, offset) ->
        val drawn = bounds(data)?.shifted(offset) ?: return@all false
        listOf(drawn.left to drawn.top, drawn.right to drawn.top, drawn.left to drawn.bottom, drawn.right to drawn.bottom)
            .all { (x, y) -> hypot(x - 54f, y - 54f) <= 33f - margin }
    }

    /** The absolute points of a path made only of absolute moves and lines ("M1,2L3,4Z"). */
    private fun points(data: String): List<Pair<Float, Float>> =
        Regex("""(-?[\d.]+),(-?[\d.]+)""").findAll(data).map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }.toList()

    @Test
    fun `the launcher icon is the Blade K inside the 66 dp safe circle, its themed icon the same shapes in one colour`() {
        val foreground = shapes(File(drawables, "ic_launcher_foreground.xml"))
        assertEquals(BLADE_K, foreground.map { it.first })
        assertEquals(listOf(0f to 0f, 0f to 0f), foreground.map { it.second })
        assertTrue(inSafeCircle(foreground))
        // Steel and a coral blade, each a gradient; graphite behind.
        assertEquals(listOf("#FFFBFAFF", "#FFAEA8C6", "#FFFFB49E", "#FFFF5B3C"), paths(parse(File(drawables, "ic_launcher_foreground.xml"))).flatMap { gradientColors(it.first) })
        assertEquals(listOf("#FF2C2A38", "#FF0F0E15"), paths(parse(File(drawables, "ic_launcher_background.xml"))).flatMap { gradientColors(it.first) })

        val monochrome = parse(File(drawables, "ic_launcher_monochrome.xml"))
        assertEquals(BLADE_K, paths(monochrome).map { it.first.attr("pathData") })
        assertTrue(paths(monochrome).all { (path, _) -> path.attr("fillColor") == "#FF000000" && gradient(path) == null })
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val layers = File("src/main/res/mipmap-anydpi", name).readText()
            listOf("background", "foreground", "monochrome").forEach { layer ->
                assertTrue("$name $layer", "<$layer android:drawable=\"@drawable/ic_launcher_$layer\" />" in layers)
            }
        }
    }

    @Test
    fun `the in-app mark is the launcher's Blade K, cut to its own box`() {
        val mark = shapes(File(drawables, "ic_brand_k.xml"))
        assertEquals(BLADE_K, mark.map { it.first })
        assertEquals(paths(parse(File(drawables, "ic_launcher_foreground.xml"))).flatMap { gradientColors(it.first) }, paths(parse(File(drawables, "ic_brand_k.xml"))).flatMap { gradientColors(it.first) })
    }

    @Test
    fun `wherever the app shows its logo it draws the Blade K on graphite, never the karaoke line on ink`() {
        val ui = File("src/main/java/app/lyrify/ui").listFiles { file -> file.extension == "kt" }.orEmpty().associate { it.name to it.readText() }
        val components = ui.getValue("Components.kt")
        // The bare K draws the in-app Blade K; the mark puts it on a graphite tile.
        assertTrue(Regex("""fun BrandK\([^\n]*\n[^\n]*R\.drawable\.ic_brand_k""").containsMatchIn(components))
        assertTrue(Regex("""fun BrandMark\([\s\S]*?background\(BrandGraphite\)[\s\S]*?BrandK\(""").containsMatchIn(components))
        // The top bar, the idle orb, onboarding, About, empty states and the AI ring's centre.
        listOf("AppShell.kt", "Chrome.kt", "OnboardingScreen.kt", "SettingsScreen.kt", "AiScreen.kt").forEach { name ->
            assertTrue(name, Regex("""BrandMark\(|BrandK\(|R\.drawable\.ic_brand_k""").containsMatchIn(ui.getValue(name)))
        }
        assertTrue(Regex("""EmptyArt\.Glyph -> BrandK\(""").containsMatchIn(components))
        // The karaoke line stays only as the "Looking up" signature and the missing cover's stand-in.
        assertEquals(emptyList<String>(), ui.filterValues { "onInk" in it || "background(BrandInk)" in it }.keys.toList())
    }

    @Test
    fun `every notification shows the Blade K small icon, one colour on a 24 unit canvas`() {
        val small = parse(File(drawables, "ic_stat_kjb.xml"))
        assertEquals("24", small.attr("viewportWidth"))
        assertEquals(listOf(BLADE_K_SMALL), paths(small).map { it.first.attr("pathData") })
        assertTrue(paths(small).all { (path, _) -> path.attr("fillColor") == "#FFFFFFFF" && gradient(path) == null })

        val calls = listOf("main", "debug", "lite", "full", "fullDebug").map { "src/$it/java" }.filter { File(it).isDirectory }.flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { SMALL_ICON.findAll(it.readText()).map { call -> call.groupValues[1] } }.toList()
        }
        assertEquals(listOf("R.drawable.ic_stat_kjb", "R.drawable.ic_stat_kjb", "R.drawable.ic_stat_kjb"), calls)
    }

    @Test
    fun `the debug build's launcher icon is told apart from the release one, on teal and, when themed, as an outline`() {
        // The colour icon: the release Blade K on a teal ground, not graphite.
        assertFalse(File(debugDrawables, "ic_launcher_foreground.xml").exists())
        assertEquals(listOf("#FF2A6B60", "#FF0E2724"), paths(parse(File(debugDrawables, "ic_launcher_background.xml"))).flatMap { gradientColors(it.first) })

        // Themed icons drop the ground, so the debug K is an outline: the Blade K at 95% about the centre, stroke and all
        // inside the safe circle.
        val monochrome = parse(File(debugDrawables, "ic_launcher_monochrome.xml"))
        val outline = paths(monochrome).map { it.first }
        assertTrue(outline.all { it.attr("fillColor") == null && it.attr("strokeColor") == "#FF000000" && it.attr("strokeWidth") == "3.5" })
        assertEquals(BLADE_K.size, outline.size)
        BLADE_K.zip(outline.map { it.attr("pathData").orEmpty() }).forEach { (k, drawn) ->
            val expected = points(k).map { (x, y) -> 54 + (x - 54) * 0.95f to 54 + (y - 54) * 0.95f }
            assertEquals(expected.size, points(drawn).size)
            expected.zip(points(drawn)).forEach { (want, got) ->
                assertEquals(want.first, got.first, 0.01f)
                assertEquals(want.second, got.second, 0.01f)
            }
        }
        assertTrue(inSafeCircle(shapes(File(debugDrawables, "ic_launcher_monochrome.xml")), margin = 3.5f / 2))
    }

    @Test
    fun `a path's bounds take relative moves, curves and arcs into account`() {
        assertEquals(Box(10f, 20f, 30f, 40f), bounds("M10,20L30,40Z"))
        assertEquals(Box(10f, 20f, 30f, 40f), bounds("m10,20 l20,20 z"))
        assertEquals(Box(100f, 100f, 300f, 200f), bounds("M100,100H300V200H100Z"))
        assertEquals(Box(0f, -5f, 20f, 10f), bounds("M0,0Q5,-5 10,0T20,10"))
        assertEquals(Box(33f, 35f, 67f, 41f), bounds("M33,35 h34 a3,3 0 0 1 0,6 h-34 a3,3 0 0 1 0,-6 z"))
        assertEquals(Box(1.5f, 0.5f, 2f, 1f), bounds("M2,1L1.5.5"))
        assertEquals(null, bounds(""))
    }

    /** A box in viewport units. */
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        operator fun plus(other: Box): Box = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

        operator fun contains(other: Box): Boolean = left <= other.left && top <= other.top && other.right <= right && other.bottom <= bottom

        fun shifted(by: Pair<Float, Float>): Box = Box(left + by.first, top + by.second, right + by.first, bottom + by.second)
    }

    private fun Element.attr(name: String): String? = getAttributeNS(ANDROID, name).takeIf { it.isNotEmpty() }

    /** The `<gradient>` [path] is filled with (`<aapt:attr name="android:fillColor">`), if any. */
    private fun gradient(path: Element): Element? =
        (0 until path.childNodes.length).map { path.childNodes.item(it) }.filterIsInstance<Element>()
            .firstOrNull { it.localName == "attr" && it.getAttribute("name") == "android:fillColor" }
            ?.getElementsByTagName("gradient")?.item(0) as? Element

    /** The colours of [path]'s gradient fill, in order; empty without one. */
    private fun gradientColors(path: Element): List<String> = gradient(path)?.let { gradient ->
        (0 until gradient.childNodes.length).map { gradient.childNodes.item(it) }.filterIsInstance<Element>().mapNotNull { it.attr("color") }
    }.orEmpty()

    private fun viewport(vector: Element): Box? {
        val width = vector.attr("viewportWidth")?.toFloatOrNull() ?: return null
        val height = vector.attr("viewportHeight")?.toFloatOrNull() ?: return null
        return if (width > 0 && height > 0) Box(0f, 0f, width, height) else null
    }

    /** Every `<path>`, with the translation of the groups around it; a group's scale or rotation is not handled here. */
    private fun paths(element: Element, offset: Pair<Float, Float> = 0f to 0f): List<Pair<Element, Pair<Float, Float>>> =
        (0 until element.childNodes.length).map { element.childNodes.item(it) }.filterIsInstance<Element>().flatMap { child ->
            when (child.tagName) {
                "path" -> listOf(child to offset)
                "group" -> {
                    val unhandled = listOf("scaleX", "scaleY", "rotation").filter { child.attr(it) != null }
                    check(unhandled.isEmpty()) { "a group with $unhandled: teach VectorIconsTest about it" }
                    val x = child.attr("translateX")?.toFloat() ?: 0f
                    val y = child.attr("translateY")?.toFloat() ?: 0f
                    paths(child, offset.first + x to offset.second + y)
                }
                else -> emptyList()
            }
        }

    /**
     * The box around [data]'s points: every end point and curve control point, absolute or
     * relative (an arc counts by its end points). Rough, but enough to tell where a path draws.
     */
    private fun bounds(data: String): Box? {
        val scan = PathScanner(data)
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        fun point(px: Float, py: Float) {
            xs += px
            ys += py
        }
        while (true) {
            val command = scan.command() ?: break
            val relative = command.isLowerCase()
            var first = true
            do {
                val ox = if (relative) x else 0f
                val oy = if (relative) y else 0f
                when (command.uppercaseChar()) {
                    'Z' -> {
                        x = startX
                        y = startY
                    }
                    'H' -> x = ox + scan.number()
                    'V' -> y = oy + scan.number()
                    'C' -> {
                        point(ox + scan.number(), oy + scan.number())
                        point(ox + scan.number(), oy + scan.number())
                        x = ox + scan.number()
                        y = oy + scan.number()
                    }
                    'S', 'Q' -> {
                        point(ox + scan.number(), oy + scan.number())
                        x = ox + scan.number()
                        y = oy + scan.number()
                    }
                    'A' -> {
                        repeat(3) { scan.number() }
                        repeat(2) { scan.flag() }
                        x = ox + scan.number()
                        y = oy + scan.number()
                    }
                    else -> {
                        // M, L, T: a point; the pairs after a move's first are lines.
                        x = ox + scan.number()
                        y = oy + scan.number()
                        if (command.uppercaseChar() == 'M' && first) {
                            startX = x
                            startY = y
                        }
                    }
                }
                point(x, y)
                first = false
            } while (command.uppercaseChar() != 'Z' && scan.hasNumber())
        }
        if (xs.isEmpty()) return null
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Reads SVG path data: commands, numbers ("1.5.5" is 1.5 then .5) and arc flags ("01" is 0 then 1). */
    private class PathScanner(private val data: String) {
        private var at = 0

        fun command(): Char? {
            skipSeparators()
            return data.getOrNull(at)?.takeIf { it.isLetter() }?.also { at++ }
        }

        fun hasNumber(): Boolean {
            skipSeparators()
            return data.getOrNull(at)?.let { it.isDigit() || it in "+-." } == true
        }

        fun number(): Float {
            skipSeparators()
            val match = NUMBER.find(data, at)?.takeIf { it.range.first == at } ?: error("no number at $at in \"$data\"")
            at = match.range.last + 1
            return match.value.toFloat()
        }

        fun flag(): Float {
            skipSeparators()
            return data.getOrNull(at++)?.digitToIntOrNull()?.toFloat() ?: error("no arc flag at ${at - 1} in \"$data\"")
        }

        private fun skipSeparators() {
            while (at < data.length && (data[at].isWhitespace() || data[at] == ',')) at++
        }

        private companion object {
            val NUMBER = Regex("""[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""")
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /** The Blade K on the 108-unit canvas (blade-k-foreground.svg): the stem and leg in steel, then the coral arm. */
        val BLADE_K = listOf(
            "M34.47,32.83L37.73,28.67L44.88,34.55L44.88,75.17L41.62,79.33L34.47,73.45ZM47.05,62.65L54.37,54.63L65.39,73L71.72,79.33L57.06,79.33Z",
            "M47.05,43.14L74.73,29.12L47.05,59.43Z",
        )

        /** The Blade K on the 24-unit canvas (blade-k-small.svg). */
        const val BLADE_K_SMALL = "M5.53,4.99L6.61,3.61L8.98,5.56L8.98,19.01L7.9,20.39L5.53,18.44ZM10,8.4L18.87,3.76L10,13.8ZM10,15.34L12.39,12.63L15.79,18.29L17.89,20.39L13.03,20.39Z"

        val SMALL_ICON = Regex("""setSmallIcon\(([^)]*)\)""")
    }
}
