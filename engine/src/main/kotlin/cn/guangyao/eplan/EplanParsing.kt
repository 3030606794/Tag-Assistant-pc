package cn.guangyao.eplan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Geometry is in displayed PDF points, origin at the upper left, before user corrections. */
internal data class PdfTextBox(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val baselineX: Float = x,
    val baselineY: Float = y + height,
    val angle: Int = 0,
    val fontSize: Float = height,
    val blue: Boolean = false,
    val advance: Float = width,
    /** Native BT and text-show operation of the first glyph; -1 means unavailable. */
    val sourceObject: Int = -1,
    val sourceRun: Int = -1,
    val sourceSequence: Int = -1,
    val sourceEndSequence: Int = sourceSequence,
) {
    val cx get() = x + width / 2f
    val cy get() = y + height / 2f
    val right get() = x + width
    val bottom get() = y + height
}

internal data class PdfPageText(
    val page: Int, val width: Float, val height: Float, val boxes: List<PdfTextBox>,
    /** A/B use native text spans; other extraction modes retain the legacy geometry view. */
    val layoutBoxes: List<PdfTextBox>? = null,
    /** PDF page display rotation, distinct from the cabinet's inferred installation direction. */
    val rotation: Int = 0,
)
internal data class Cn1Wire(val text: String, val um: String)
internal data class PdfPageScope(
    val page: Int,
    val cabinet: String = "",
    val higher: String = "",
    val type: String = "",
    val title: String = "",
)

/** Pure parsing helpers; Android/PDF decoding and file IO stay in OfflinePdfEngine. */
internal object EplanParsing {
    private val id = Regex("-?[0-9]*[A-Za-z]{1,10}[0-9]+(?:[./-](?:[A-Za-z]{0,10})?[0-9]+)*")
    private val category = Regex("^([0-9]*)([A-Za-z]+)([0-9]+)")
    private val um = "-?(?:[0-9]*UM)[0-9]+(?:/(?:UM)?[0-9]+)*"
    private val endDevice = "-?[0-9]*[A-Za-z]+[0-9]+(?:[.-][A-Za-z0-9]+)*"
    private val pin = "[-+A-Za-z0-9_.]+"
    private val endpoint = "$endDevice(?::$pin){1,3}"
    private val cnEnd = "($um):CN1:($pin)"
    private val leftCn1 = Regex("^$cnEnd\\s*/\\s*($endpoint)$", RegexOption.IGNORE_CASE)
    private val rightCn1 = Regex("^($endpoint)\\s*/\\s*$cnEnd$", RegexOption.IGNORE_CASE)
    private val excludedPage = Regex("孔位|钻孔|功能文本|功能表|目录|封面|部件|BOM|控制简图|接线表", RegexOption.IGNORE_CASE)
    private val nonWiring = Regex("AAA|AAB|ADB|EPA|ELU", RegexOption.IGNORE_CASE)

    fun selections(value: String): Set<String> = value.split(Regex("[\\s,，、;；]+")).map { it.trim().removePrefix("+") }
        .filter { it.isNotBlank() }.map { it.uppercase() }.toSet()

    fun overrides(value: String): Map<String, String> = value.lineSequence().mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0 || split == line.lastIndex) null
        else line.substring(0, split).trim().uppercase() to line.substring(split + 1).trim()
    }.toMap()

    fun acceptsCabinet(cabinet: String, rules: RuleSettings): Boolean {
        val name = cabinet.removePrefix("+").uppercase()
        val only = selections(rules.cabinetFilter)
        val excluded = selections(rules.excludedCabinets)
        return (only.isEmpty() || name in only) && name !in excluded
    }

    fun isLayout(scope: PdfPageScope): Boolean = !excludedPage.containsMatchIn(scope.title) &&
        (scope.type.equals("ELU1", true) || Regex("安装板布局|布局图|安装板布置|柜内布置").containsMatchIn(scope.title))

    fun isFunctionTable(scope: PdfPageScope) = Regex("功能文本|功能表").containsMatchIn(scope.title)

    fun isWiring(scope: PdfPageScope): Boolean = !nonWiring.containsMatchIn(scope.type) &&
        !Regex("孔位|钻孔|功能文本|目录|封面|部件|BOM", RegexOption.IGNORE_CASE).containsMatchIn(scope.title)

    fun needsLayout(mode: ExtractionMode) = mode != ExtractionMode.CN1
    fun needsFunction(mode: ExtractionMode) = mode in setOf(ExtractionMode.FULL, ExtractionMode.FUNCTION, ExtractionMode.QUANTITY)
    fun needsCn1(mode: ExtractionMode) = mode in setOf(ExtractionMode.FULL, ExtractionMode.CN1, ExtractionMode.QUANTITY)

    fun deviceId(text: String, requireLeader: Boolean = true): String? {
        val compact = text.trim().replace(Regex("\\s+"), "")
        if (requireLeader && !compact.startsWith('-')) return null
        if (!id.matches(compact)) return null
        return compact.removePrefix("-")
    }

    fun deviceCategory(text: String): String = category.find(text.removePrefix("-"))?.groupValues?.get(2)?.uppercase().orEmpty()
    fun categoryPrefix(text: String): String = category.find(text.removePrefix("-"))?.let { it.groupValues[1] + it.groupValues[2].uppercase() }.orEmpty()
    fun isUm(text: String) = Regex("^[0-9]*UM[0-9]+(?:/(?:UM)?[0-9]+)*$", RegexOption.IGNORE_CASE).matches(text)
    fun isXt(text: String) = deviceCategory(text) == "XT"
    fun umAxisCount(text: String) = text.split('/').size

    /** Validate both complete endpoints without splitting the slashes inside a combined UM. */
    fun cn1Wire(text: String): Cn1Wire? {
        val original = text.trim()
        if (!original.contains(":CN1:", true)) return null
        val left = leftCn1.matchEntire(original)
        val right = rightCn1.matchEntire(original)
        val name = left?.groupValues?.get(1) ?: right?.groupValues?.get(2) ?: return null
        return Cn1Wire(original, name.removePrefix("-"))
    }

    fun defaultFunction(device: String): String? = when (deviceCategory(device)) {
        "ECT", "IM" -> "耦合器"
        "AI" -> "模拟量输入"
        "AO" -> "模拟量输出"
        "DI" -> "数字量输入"
        "DO" -> "数字量输出"
        "PM" -> "电能表"
        "SCR" -> "安全控制器"
        "PS" -> "电源模块"
        "TA" -> category.find(device)?.groupValues?.get(3)?.toIntOrNull()?.takeIf { it > 0 }?.let { "电流互感器-L${(it - 1) % 3 + 1}" }
        else -> null
    }

    fun isFunction(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t in setOf("=", "—", "/", "无", "未标注")) return false
        if (t.startsWith("-") && deviceId(t) != null) return false
        if (Regex("^[0-9. ×xX*+\\-/():]+(?:mm|cm|V|A|W|kW)?$", RegexOption.IGNORE_CASE).matches(t)) return false
        if (Regex("^[=+&]|^/[0-9]|页描述|项目描述|总页|安装板|孔位|^功能文本|^设备标识符|^类型号").containsMatchIn(t)) return false
        return t.any { it in '\u3400'..'\u9fff' } ||
            Regex("^(?:STO|CN1|UM[0-9]+(?:/[0-9]+)*|L[123]|N24|P24|PE)$", RegexOption.IGNORE_CASE).matches(t)
    }

    fun rotate(x: Float, y: Float, width: Float, height: Float, clockwise: Int): Pair<Float, Float> = when ((clockwise % 360 + 360) % 360) {
        90 -> height - y to x
        180 -> width - x to height - y
        270 -> y to width - x
        else -> x to y
    }

    /** Cluster actual rows with a fixed seed; a chain of slightly staggered labels cannot swallow the next row. */
    fun readingOrder(boxes: List<PdfTextBox>, width: Float, height: Float, clockwise: Int = 0): List<PdfTextBox> {
        if (boxes.size < 2) return boxes
        data class Positioned(val box: PdfTextBox, val x: Float, val y: Float)
        val positions = boxes.map { box -> rotate(box.cx, box.cy, width, height, clockwise).let { Positioned(box, it.first, it.second) } }
            .sortedBy { it.y }
        val font = boxes.map { it.fontSize }.sorted()[boxes.size / 2].coerceAtLeast(2f)
        val rows = mutableListOf<MutableList<Positioned>>()
        val tolerance = font * 1.8f
        for (p in positions) {
            val row = rows.lastOrNull()
            if (row != null && abs(p.y - row.first().y) <= tolerance) row.add(p) else rows.add(mutableListOf(p))
        }
        return rows.flatMap { it.sortedBy { p -> p.x }.map { p -> p.box } }
    }

    /** Join glyphs by their true baseline (including diagonal labels) and color, never by PDF stream order. */
    fun joinGlyphs(glyphs: List<PdfTextBox>): List<PdfTextBox> {
        val words = mutableListOf<PdfTextBox>()
        for ((_, angled) in glyphs.groupBy { it.angle to it.blue }) {
            val angle = Math.toRadians(angled.first().angle.toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            fun along(g: PdfTextBox) = g.baselineX * dx + g.baselineY * dy
            fun across(g: PdfTextBox) = -g.baselineX * dy + g.baselineY * dx
            val rows = mutableListOf<MutableList<PdfTextBox>>()
            for (glyph in angled.sortedBy { across(it) }) {
                val row = rows.lastOrNull()
                if (row != null && abs(across(glyph) - across(row.first())) < max(0.55f, glyph.fontSize * .23f)) row.add(glyph)
                else rows.add(mutableListOf(glyph))
            }
            for (row in rows) {
                val run = mutableListOf<PdfTextBox>()
                fun flush() {
                    if (run.isEmpty()) return
                    val text = run.joinToString("") { it.text }.trim()
                    if (text.isNotEmpty()) {
                        val first = run.first()
                        val x = run.minOf { it.x }; val y = run.minOf { it.y }
                        words += PdfTextBox(text, x, y, run.maxOf { it.right } - x, run.maxOf { it.bottom } - y,
                            first.baselineX, first.baselineY, first.angle, first.fontSize, first.blue,
                            run.maxOf { along(it) + it.advance } - along(first))
                    }
                    run.clear()
                }
                var previous: PdfTextBox? = null
                var previousEnd = 0f
                for (glyph in row.sortedBy { along(it) }) {
                    val prev = previous
                    val advance = glyph.advance
                    if (prev != null && (along(glyph) - previousEnd > max(1f, glyph.fontSize * .65f) ||
                        abs(glyph.fontSize - prev.fontSize) > max(1f, glyph.fontSize * .35f))) flush()
                    // Duplicate overprint at the exact same physical location is not a second installed device.
                    if (prev == null || prev.text != glyph.text || hypot((prev.baselineX - glyph.baselineX).toDouble(), (prev.baselineY - glyph.baselineY).toDouble()) > .15) {
                        run += glyph
                    }
                    previous = glyph
                    previousEnd = along(glyph) + advance
                }
                flush()
            }
        }
        return words
    }

    /**
     * Reconstruct a native text operation before comparing it with neighbouring operations.
     * Dense EPLAN annotations can actually overlap: sorting all their glyphs by X corrupts
     * both statements. A text-show operation is evidence of continuity, not a label boundary:
     * TJ can jump columns, and one real label can span many Tj/font/BT changes.
     */
    fun joinNativeGlyphs(glyphs: List<PdfTextBox>, checkCancelled: () -> Unit = {}): List<PdfTextBox> {
        if (glyphs.isEmpty()) return emptyList()
        // Old callers/tests have no native provenance. Their established interpretation stays intact.
        if (glyphs.any { it.sourceRun < 0 || it.sourceSequence < 0 }) return joinGlyphs(glyphs)
        data class Fragment(val box: PdfTextBox, val lastObject: Int, val lastRun: Int)
        fun along(box: PdfTextBox, angle: Int = box.angle): Float {
            val radians = Math.toRadians(angle.toDouble())
            return box.baselineX * cos(radians).toFloat() + box.baselineY * sin(radians).toFloat()
        }
        fun baselineDistance(a: PdfTextBox, b: PdfTextBox): Float {
            val radians = Math.toRadians(a.angle.toDouble())
            return abs(-(b.baselineX - a.baselineX) * sin(radians).toFloat() +
                (b.baselineY - a.baselineY) * cos(radians).toFloat())
        }
        fun compatible(a: PdfTextBox, b: PdfTextBox) = a.angle == b.angle && a.blue == b.blue &&
            abs(a.fontSize - b.fontSize) <= max(1f, minOf(a.fontSize, b.fontSize) * .35f)
        fun duplicate(a: PdfTextBox, b: PdfTextBox) = a.text == b.text && compatible(a, b) &&
            hypot((a.baselineX - b.baselineX).toDouble(), (a.baselineY - b.baselineY).toDouble()) < .15
        fun combine(values: List<PdfTextBox>): PdfTextBox {
            val first = values.first()
            val left = values.minOf { it.x }; val top = values.minOf { it.y }
            return first.copy(text = values.joinToString("") { it.text }, x = left, y = top,
                width = values.maxOf { it.right } - left, height = values.maxOf { it.bottom } - top,
                advance = values.maxOf { along(it, first.angle) + it.advance } - along(first),
                sourceEndSequence = values.last().sourceEndSequence)
        }
        val fragments = mutableListOf<Fragment>()
        val current = mutableListOf<PdfTextBox>()
        fun flush() {
            if (current.isEmpty()) return
            val box = combine(current)
            if (box.text.isNotEmpty()) fragments += Fragment(box, current.last().sourceObject, current.last().sourceRun)
            current.clear()
        }
        for ((index, glyph) in glyphs.sortedBy { it.sourceSequence }.withIndex()) {
            if (index % 256 == 0) checkCancelled()
            val previous = current.lastOrNull()
            if (previous != null) {
                val font = minOf(previous.fontSize, glyph.fontSize)
                val gap = along(glyph, previous.angle) - along(previous) - previous.advance
                if (previous.sourceRun == glyph.sourceRun && previous.sourceObject == glyph.sourceObject && duplicate(previous, glyph)) {
                    current[current.lastIndex] = previous.copy(sourceEndSequence = glyph.sourceEndSequence)
                    continue
                }
                if (previous.sourceRun != glyph.sourceRun || previous.sourceObject != glyph.sourceObject ||
                    !compatible(previous, glyph) || baselineDistance(current.first(), glyph) > max(.15f, font * .12f) ||
                    gap > max(1f, font * .65f) || gap < -max(.2f, font * .18f)) {
                    flush()
                }
            }
            current += glyph
        }
        flush()

        val numberedCaption = Regex("^[0-9]+\\s*[:：]")
        val result = mutableListOf<PdfTextBox>()
        val pending = mutableListOf<Fragment>()
        var pendingEnd = 0f
        var pendingPrefix = ""
        fun finishPending() {
            if (pending.isNotEmpty()) {
                val box = combine(pending.map { it.box })
                if (box.text.isNotBlank()) result += box.copy(text = box.text.trim())
            }
            pending.clear()
            pendingPrefix = ""
        }
        for ((index, next) in fragments.withIndex()) {
            if (index % 256 == 0) checkCancelled()
            val previous = pending.lastOrNull()
            val b = next.box
            if (previous == null) {
                pending += next
                pendingEnd = along(b) + b.advance
                pendingPrefix = b.text.take(64)
                continue
            }
            val a = pending.first().box
            if (pending.size == 1 && duplicate(a, b)) {
                pending[0] = previous.copy(box = a.copy(sourceEndSequence = b.sourceEndSequence),
                    lastObject = next.lastObject, lastRun = next.lastRun)
                continue
            }
            val font = minOf(a.fontSize, b.fontSize)
            val sameObject = previous.lastObject >= 0 && previous.lastObject == b.sourceObject
            val gap = along(b, a.angle) - pendingEnd
            // Safe continuation is local in the original stream AND at the previous text's end.
            // Separate numbered channels stay separate even if a crowded export leaves no gap.
            val continuation = b.sourceSequence == previous.box.sourceEndSequence + 1 && compatible(a, b) &&
                baselineDistance(a, b) <= max(.10f, font * .08f) &&
                gap >= -max(.10f, font * .08f) && gap <= max(.15f, font * if (sameObject) .35f else .15f) &&
                !(numberedCaption.containsMatchIn(pendingPrefix.trimStart()) && numberedCaption.containsMatchIn(b.text.trimStart())) &&
                !(deviceId(pendingPrefix) != null && deviceId(b.text) != null)
            if (continuation) {
                pending += next
                pendingEnd = max(pendingEnd, along(b, a.angle) + b.advance)
                pendingPrefix = (pendingPrefix + b.text.take(64)).take(64)
            } else {
                finishPending()
                pending += next
                pendingEnd = along(b) + b.advance
                pendingPrefix = b.text.take(64)
            }
        }
        finishPending()
        checkCancelled()
        return result
    }

    fun mergeBoxes(boxes: List<PdfTextBox>, text: String): PdfTextBox {
        val first = boxes.first()
        val x = boxes.minOf { it.x }; val y = boxes.minOf { it.y }
        return first.copy(text = text, x = x, y = y, width = boxes.maxOf { it.right } - x, height = boxes.maxOf { it.bottom } - y)
    }

    fun normalizeAngle(angle: Double): Int = ((angle.roundToInt() % 360) + 360) % 360
}
