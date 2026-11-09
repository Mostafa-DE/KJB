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
 * notification icon are the Note arm K (the Blade K with an eighth note for its arm), exactly as its source SVGs draw it.
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

    // The Note arm K

    /** [file]'s shapes as drawn: each path's data, with the translation of the groups around it. */
    private fun shapes(file: File): List<Pair<String, Pair<Float, Float>>> = paths(parse(file)).map { (path, offset) -> path.attr("pathData").orEmpty() to offset }

    /**
     * Whether every point of each of [shapes] (the ends of its lines, and of its curves with their control points, which
     * hold the curve) lies within the adaptive icon's 66 dp safe circle (108-unit canvas), [margin] inside it (half a
     * stroke's width). For paths of absolute commands, as the icon's are.
     */
    private fun inSafeCircle(shapes: List<Pair<String, Pair<Float, Float>>>, margin: Float = 0f): Boolean = shapes.all { (data, offset) ->
        points(data).isNotEmpty() && points(data).all { (x, y) -> hypot(x + offset.first - 54f, y + offset.second - 54f) <= 33f - margin }
    }

    /** The absolute points of a path made only of absolute commands ("M1,2L3,4C5,6 7,8 9,10Z"). */
    private fun points(data: String): List<Pair<Float, Float>> =
        Regex("""(-?[\d.]+),(-?[\d.]+)""").findAll(data).map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }.toList()

    private fun colors(file: File): List<String> = paths(parse(file)).flatMap { gradientColors(it.first) }

    @Test
    fun `the launcher icon is the Note arm K inside the 66 dp safe circle, its themed icon the same shapes in one colour`() {
        val foreground = shapes(File(drawables, "ic_launcher_foreground.xml"))
        assertEquals(NOTE_ARM, foreground.map { it.first })
        assertEquals(List(NOTE_ARM.size) { 0f to 0f }, foreground.map { it.second })
        assertTrue(inSafeCircle(foreground))
        // A steel stem and leg, then the coral note: head, stem and flag, each a gradient.
        assertEquals(listOf("#FFFBFAFF", "#FFAEA8C6") + List(3) { listOf("#FFFFB49E", "#FFFF5B3C") }.flatten(), colors(File(drawables, "ic_launcher_foreground.xml")))
        // True black, its edge lifting toward the mask so the tile keeps a faint rim on a black home screen.
        val background = paths(parse(File(drawables, "ic_launcher_background.xml"))).map { it.first }
        assertEquals("radial", gradient(background.single())?.attr("type"))
        assertEquals(listOf("#FF0A0A0B", "#FF0A0A0B", "#FF161618", "#FF232326"), colors(File(drawables, "ic_launcher_background.xml")))

        val monochrome = parse(File(drawables, "ic_launcher_monochrome.xml"))
        assertEquals(NOTE_ARM, paths(monochrome).map { it.first.attr("pathData") })
        assertTrue(paths(monochrome).all { (path, _) -> path.attr("fillColor") == "#FF000000" && gradient(path) == null })
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val layers = File("src/main/res/mipmap-anydpi", name).readText()
            listOf("background", "foreground", "monochrome").forEach { layer ->
                assertTrue("$name $layer", "<$layer android:drawable=\"@drawable/ic_launcher_$layer\" />" in layers)
            }
        }
    }

    @Test
    fun `the in-app mark is the launcher's Note arm K, cut to its own box`() {
        val mark = shapes(File(drawables, "ic_brand_k.xml"))
        assertEquals(NOTE_ARM, mark.map { it.first })
        assertEquals(colors(File(drawables, "ic_launcher_foreground.xml")), colors(File(drawables, "ic_brand_k.xml")))
    }

    @Test
    fun `wherever the app shows its logo it draws the Note arm K on black, never the karaoke line on ink`() {
        val ui = File("src/main/java/app/lyrify/ui").listFiles { file -> file.extension == "kt" }.orEmpty().associate { it.name to it.readText() }
        val components = ui.getValue("Components.kt")
        // The bare K draws the in-app mark; the brand mark puts it on a black tile.
        assertTrue(Regex("""fun BrandK\([^\n]*\n[^\n]*R\.drawable\.ic_brand_k""").containsMatchIn(components))
        assertTrue(Regex("""fun BrandMark\([\s\S]*?background\(BrandBlack\)[\s\S]*?BrandK\(""").containsMatchIn(components))
        // Companion Now's top bar, onboarding, About, empty states and the AI ring's centre.
        listOf("CompanionNowScreen.kt", "OnboardingScreen.kt", "SettingsScreen.kt", "AiScreen.kt").forEach { name ->
            assertTrue(name, Regex("""BrandMark\(|BrandK\(|R\.drawable\.ic_brand_k""").containsMatchIn(ui.getValue(name)))
        }
        assertTrue(Regex("""EmptyArt\.Glyph -> BrandK\(""").containsMatchIn(components))
        // The karaoke line stays only as the "Looking up" signature and the missing cover's stand-in.
        assertEquals(emptyList<String>(), ui.filterValues { "onInk" in it || "background(BrandInk)" in it }.keys.toList())
    }

    @Test
    fun `every notification shows the Note arm K small icon, one colour on a 24 unit canvas`() {
        val small = parse(File(drawables, "ic_stat_kjb.xml"))
        assertEquals("24", small.attr("viewportWidth"))
        assertEquals(NOTE_ARM_SMALL, paths(small).map { it.first.attr("pathData") })
        assertTrue(paths(small).all { (path, _) -> path.attr("fillColor") == "#FFFFFFFF" && gradient(path) == null })

        val calls = listOf("src/main/java", "src/debug/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { SMALL_ICON.findAll(it.readText()).map { call -> call.groupValues[1] } }.toList()
        }
        // The AI's night run, the model download, the debug build's AI notice, and the player's media notification.
        assertEquals(List(4) { "R.drawable.ic_stat_kjb" }, calls)
    }

    @Test
    fun `the debug build's launcher icon is told apart from the release one by a DEV tab, on the same black`() {
        assertFalse(File(debugDrawables, "ic_launcher_background.xml").exists())
        // The release K, then a steel tab in the K's open side with DEV cut through it, readable at 48 px.
        val foreground = paths(parse(File(debugDrawables, "ic_launcher_foreground.xml"))).map { it.first }
        assertEquals(NOTE_ARM + DEV_TAB, foreground.map { it.attr("pathData") })
        assertEquals(colors(File(drawables, "ic_launcher_foreground.xml")), colors(File(debugDrawables, "ic_launcher_foreground.xml")))
        assertEquals("#FFE9E6F5", foreground.last().attr("fillColor"))
        assertEquals("evenOdd", foreground.last().attr("fillType"))
        assertTrue(inSafeCircle(shapes(File(debugDrawables, "ic_launcher_foreground.xml"))))
        // The themed icon keeps the tab: the same shapes in one colour.
        val monochrome = paths(parse(File(debugDrawables, "ic_launcher_monochrome.xml"))).map { it.first }
        assertEquals(NOTE_ARM + DEV_TAB, monochrome.map { it.attr("pathData") })
        assertTrue(monochrome.all { it.attr("fillColor") == "#FF000000" && gradient(it) == null })
        assertEquals("evenOdd", monochrome.last().attr("fillType"))
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

        /**
         * The Note arm K on the 108-unit canvas (blade-k-note-foreground.svg): the stem and leg in steel, then the arm as a
         * coral eighth note, its head in the K's joint, its stem the arm, its flag a blade.
         */
        val NOTE_ARM = listOf(
            "M30.61,33.37L33.78,29.32L40.75,35.04L40.75,74.63L37.57,78.68L30.61,72.96ZM42.86,63.39L51.02,57.28L60.16,72.51L66.33,78.68L52.04,78.68Z",
            "M54.93,47.42C56,49.82 54.24,52.94 50.99,54.38C47.74,55.83 44.24,55.05 43.17,52.65C42.1,50.25 43.87,47.13 47.12,45.68C50.36,44.24 53.86,45.01 54.93,47.42Z",
            "M49.39,50.29L65.53,29.32L70.87,29.32L52.74,52.87Z",
            "M65.53,29.32L70.87,29.32L76.86,31.61L77.39,44.3L64.42,37.69Z",
        )

        /** The debug build's DEV tab: a steel plate with chamfered corners and the letters D, E and V cut through it. */
        const val DEV_TAB = "M60,47L84,47L86,49L86,61L84,63L60,63L58,61L58,49ZM60.45,50.25L64.05,50.25C65.95,50.25 66.95,51.85 66.95,55C66.95,58.15 65.95,59.75 64.05,59.75L60.45,59.75ZM62.65,52.25L63.75,52.25C64.45,52.25 64.75,53.15 64.75,55C64.75,56.85 64.45,57.75 63.75,57.75L62.65,57.75ZM68.75,50.25L74.95,50.25L74.95,52.25L70.95,52.25L70.95,54.05L74.35,54.05L74.35,55.95L70.95,55.95L70.95,57.75L74.95,57.75L74.95,59.75L68.75,59.75ZM77.05,50.25L79.35,50.25L80.3,56.55L81.25,50.25L83.55,50.25L81.45,59.75L79.15,59.75Z"

        /** The Note arm K on the 24-unit canvas (blade-k-note-small.svg). */
        val NOTE_ARM_SMALL = listOf(
            "M4.26,5.16L5.31,3.82L7.62,5.72L7.62,18.84L6.57,20.18L4.26,18.28ZM8.67,15.55L11.37,13.53L14.14,18.14L16.19,20.18L11.45,20.18Z",
            "M12.67,9.82C13.03,10.61 12.44,11.65 11.37,12.13C10.29,12.61 9.13,12.35 8.77,11.55C8.42,10.76 9.01,9.72 10.08,9.24C11.16,8.76 12.32,9.02 12.67,9.82Z",
            "M10.85,10.78L15.85,3.82L17.58,3.82L11.99,11.6Z",
            "M15.85,3.82L17.58,3.82L19.57,4.58L19.74,8.79L15.58,6.59Z",
        )

        val SMALL_ICON = Regex("""setSmallIcon\(([^)]*)\)""")
    }
}
