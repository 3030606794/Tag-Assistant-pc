package cn.guangyao.eplan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Source geometry travels with a function; a repeated device name is never its physical identity. */
internal data class AbFunctionEvidence(val text: String, val box: PdfTextBox, val page: Int)
internal data class AbInstalledPosition(val box: PdfTextBox, val region: Int, val rotation: Int)

/** Deterministic A/B interpretation. This class does not read files, execute PDF actions or prompt users. */
internal object AbLayoutAnalysis {
    private val numberedChannel = Regex("^\\s*([0-9]{1,3})\\s*[:：]\\s*\\S")
    private data class TableTemplate(
        val device: PdfTextBox,
        val model: PdfTextBox,
        val supplier: PdfTextBox,
        val function: PdfTextBox,
    )
    private data class Panel(val center: Float, val template: TableTemplate, val rows: List<PdfTextBox>)
    private data class Point(val x: Float, val y: Float)

    /**
     * Start with labels in installation space, then distinguish a main device label from a
     * device-shaped reference printed in a separate description field. The latter requires
     * corroborating property rectangles and model typography; angle alone never removes an ID.
     */
    fun layoutDevices(
        page: PdfPageText,
        linkAreas: List<PdfTextBox> = emptyList(),
        checkCancelled: () -> Unit = {},
        recoverUnmarkedIds: Boolean = false,
    ): List<PdfTextBox> {
        checkCancelled()
        if (recoverUnmarkedIds && page.rotation != 0) {
            val turn = (360 - page.rotation) % 360
            val boxes = page.boxes.associateWith { corrected(it, page, turn) }
            val originals = boxes.entries.associate { it.value to it.key }
            val view = unrotated(page, boxes.values.toList())
            return layoutDevices(view, linkAreas.map { corrected(it, page, turn) }, checkCancelled, true)
                .map { originals.getValue(it) }
        }
        val candidates = page.boxes.filter { box ->
            val id = EplanParsing.deviceId(box.text)
            id != null && box.cx > page.width * .025f && box.cx < page.width * .98f &&
                box.cy > page.height * .035f && box.cy < page.height * .915f &&
                !(EplanParsing.deviceCategory(id) in setOf("CS", "GR") && page.boxes.any { other ->
                    Regex("[0-9](?:[0-9. ×xX*]*)(?:mm|毫米)|^[0-9]+[×*][0-9]+$", RegexOption.IGNORE_CASE)
                        .containsMatchIn(other.text) && rectangleDistance(box, other) < max(30f, box.fontSize * 6f)
                })
        }
        val recovered = if (recoverUnmarkedIds) page.boxes.filter { box ->
            checkCancelled()
            EplanParsing.deviceId(box.text, requireLeader = false) != null &&
                EplanParsing.deviceId(box.text) == null &&
                isUnmarkedInstallation(page, box, candidates, linkAreas, checkCancelled)
        } else emptyList()
        val allCandidates = candidates + recovered
        val bindings = propertyFieldOwners(page, allCandidates, linkAreas, checkCancelled)
        return allCandidates.filter { candidate ->
            checkCancelled()
            val owner = bindings[candidate]
            if (owner == null || owner == candidate || candidate.fontSize > owner.fontSize * .82f) true
            else {
                val modelBesideReference = page.boxes.any { model ->
                    !model.blue && Regex("[A-Za-z][A-Za-z0-9_/-]{2,}").containsMatchIn(model.text) &&
                        angleDifference(model.angle, candidate.angle) <= 3 &&
                        linkAreas.any { field ->
                            field.width < page.width * .25f && field.height < page.height * .35f &&
                                inside(Point(model.cx, model.cy), field, .3f) &&
                                inside(Point(candidate.cx, candidate.cy), field, .3f)
                        }
                }
                !modelBesideReference
            }
        }
    }

    /** Only the display's device prefix is removed. Source evidence and internal punctuation survive. */
    fun normalizeAbDisplayText(text: String): String {
        val value = text.trim()
        return if (EplanParsing.deviceId(value) != null) value.removePrefix("-") else text
    }

    /**
     * A missing '-' is not permission to treat every model or caption as a new component.
     * Require matching main-ID typography and a corroborating installation property block:
     * either a tall paired body, or a complete inline block with native caption/model evidence.
     */
    private fun isUnmarkedInstallation(
        page: PdfPageText,
        candidate: PdfTextBox,
        marked: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Boolean {
        if (!candidate.blue || candidate.cx <= page.width * .025f || candidate.cx >= page.width * .98f ||
            candidate.cy <= page.height * .035f || candidate.cy >= page.height * .915f ||
            angleDifference(candidate.angle, quadrant(candidate.angle)) > 3) return false
        if (marked.none { it.blue && angleDifference(it.angle, candidate.angle) <= 3 &&
                candidate.fontSize / it.fontSize.coerceAtLeast(.1f) in .8f..1.25f }) return false
        val rotation = (270 - quadrant(candidate.angle) + 360) % 360
        val id = corrected(candidate, page, rotation)
        val rects = installationFields(page, areas).map { corrected(it, page, rotation) }
        val markedPositions = marked.map { corrected(it, page, rotation) }
        val captions = page.boxes.filter { it != candidate && it.blue && EplanParsing.isFunction(it.text) &&
            EplanParsing.deviceId(it.text, requireLeader = false) == null }
            .map { corrected(it, page, rotation) }
        for (main in rects) {
            checkCancelled()
            if (!inside(Point(id.cx, id.cy), main, .3f) || main.height < id.height * 1.6f ||
                id.y > main.y + max(id.fontSize, main.height * .1f) ||
                markedPositions.any { inside(Point(it.cx, it.cy), main, .3f) }) continue
            for (description in rects) {
                if (!pairedSideField(main, description, id.fontSize)) continue
                if (captions.any { caption ->
                        inside(Point(caption.baselineX, caption.baselineY), description, .4f) &&
                            caption.baselineY > main.y + main.height * .5f
                    }) return true
            }
        }
        return isUnmarkedInlineInstallation(page, candidate, marked, areas, checkCancelled)
    }

    /**
     * Other EPLAN layouts split one body into adjacent ID/body/description cells (1:2:1),
     * so the ID cell alone is short. Native ID plus caption/model runs and the complete
     * block prove an installed component; a bare model number or a stray reference does not.
     */
    private fun isUnmarkedInlineInstallation(
        page: PdfPageText,
        candidate: PdfTextBox,
        marked: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Boolean {
        fun native(box: PdfTextBox) = box.sourceObject >= 0 && box.sourceRun >= 0 &&
            box.sourceSequence >= 0 && box.sourceEndSequence >= box.sourceSequence
        if (!native(candidate)) return false
        val next = page.boxes.asSequence().filter(::native)
            .filter { it.sourceSequence > candidate.sourceEndSequence }.sortedBy { it.sourceSequence }.take(2).toList()
        if (next.size != 2 || next[0].sourceRun <= candidate.sourceRun ||
            next[1].sourceRun <= next[0].sourceRun || next[0].sourceEndSequence >= next[1].sourceSequence) return false
        val caption = next.singleOrNull { it.blue } ?: return false
        val model = next.singleOrNull { !it.blue } ?: return false
        if (!confirmedFunction(caption.text) || EplanParsing.deviceId(caption.text, requireLeader = false) != null ||
            channelNumber(caption.text) != null ||
            !Regex("[A-Za-z][A-Za-z0-9_./-]*[0-9]").containsMatchIn(model.text) ||
            angleDifference(model.angle, caption.angle) > 3 ||
            caption.fontSize / model.fontSize.coerceAtLeast(.1f) !in .8f..1.25f) return false

        if (isUnmarkedNativeTallBody(page, candidate, caption, model, marked, areas, checkCancelled)) return true

        val rotation = (360 - quadrant(candidate.angle)) % 360
        val id = corrected(candidate, page, rotation)
        val function = corrected(caption, page, rotation)
        val type = corrected(model, page, rotation)
        val rects = installationFields(page, areas).map { corrected(it, page, rotation) }
        val markedPositions = marked.map { corrected(it, page, rotation) }
        val tolerance = max(.6f, id.fontSize * .2f)
        for (head in rects) {
            checkCancelled()
            if (!inside(Point(id.cx, id.cy), head, .3f) ||
                abs(id.baselineX - head.x) > tolerance || id.right > head.right + tolerance) continue
            fun sameExtent(other: PdfTextBox) = abs(other.y - head.y) <= tolerance &&
                abs(other.bottom - head.bottom) <= tolerance
            val middles = rects.filter { sameExtent(it) && abs(it.x - head.right) <= tolerance &&
                abs(it.width - head.width * 2f) <= max(.6f, head.width * .12f) }
            for (middle in middles) for (tail in rects) {
                checkCancelled()
                if (!sameExtent(tail) || abs(tail.x - middle.right) > tolerance ||
                    abs(tail.width - head.width) > max(.6f, head.width * .08f) ||
                    !inside(Point(function.baselineX, function.baselineY), tail, .3f) ||
                    !inside(Point(type.baselineX, type.baselineY), tail, .3f)) continue
                val block = PdfTextBox("", head.x, head.y, tail.right - head.x, head.height)
                if (block.width >= block.height * 1.6f &&
                    markedPositions.none { inside(Point(it.cx, it.cy), block, .3f) }) return true
            }
        }
        return false
    }

    /**
     * A combined servo can span more than the generic field-height limit. Its native triplet
     * and a complete elongated 1:2:1 body permit this exception without admitting large loose
     * hyperlinks into the generic caption matchers. Both properties must start in its ID cell.
     */
    private fun isUnmarkedNativeTallBody(
        page: PdfPageText,
        candidate: PdfTextBox,
        caption: PdfTextBox,
        model: PdfTextBox,
        marked: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Boolean {
        val rotation = (270 - quadrant(candidate.angle) + 360) % 360
        val id = corrected(candidate, page, rotation)
        val function = corrected(caption, page, rotation)
        val type = corrected(model, page, rotation)
        val width = if (rotation % 180 == 0) page.width else page.height
        val height = if (rotation % 180 == 0) page.height else page.width
        val rects = areas.map { corrected(it, page, rotation) }.filter {
            it.width > 0f && it.width < width * .2f && it.height > 0f && it.height < height * .8f
        }.distinctBy { listOf(it.x, it.y, it.width, it.height) }
        val others = marked.map { corrected(it, page, rotation) }
        val tolerance = max(.6f, id.fontSize * .2f)
        for (head in rects) {
            checkCancelled()
            if (head.height < head.width * 4f || head.height < id.height * 1.6f ||
                abs(head.y - id.y) > tolerance || !inside(Point(id.cx, id.cy), head, .3f) ||
                function.baselineY <= id.bottom + function.fontSize ||
                type.baselineY <= id.bottom + type.fontSize ||
                !inside(Point(function.baselineX, function.baselineY), head, .3f) ||
                !inside(Point(type.baselineX, type.baselineY), head, .3f)) continue
            fun sameExtent(other: PdfTextBox) = abs(other.y - head.y) <= tolerance &&
                abs(other.bottom - head.bottom) <= tolerance
            for (middle in rects) {
                if (!sameExtent(middle) || abs(middle.width - head.width * 2f) > max(.6f, head.width * .12f)) continue
                val rightward = abs(middle.x - head.right) <= tolerance
                val leftward = abs(middle.right - head.x) <= tolerance
                if (!rightward && !leftward) continue
                for (last in rects) {
                    checkCancelled()
                    if (!sameExtent(last) || abs(last.width - head.width) > max(.6f, head.width * .08f) ||
                        !(rightward && abs(last.x - middle.right) <= tolerance ||
                            leftward && abs(last.right - middle.x) <= tolerance)) continue
                    // A long diagonal caption expands these fields across the next rail.
                    // Reject another main at this ID's head, not unrelated lower-rail text.
                    if (others.none { inside(Point(it.cx, it.cy), head, .3f) &&
                            it.y <= id.bottom + tolerance }) return true
                }
            }
        }
        return false
    }

    private fun installationFields(page: PdfPageText, areas: List<PdfTextBox>) = areas.filter {
        it.width > 0f && it.height > 0f && it.width < page.width * .35f && it.height < page.height * .35f
    }.distinctBy { listOf(it.x, it.y, it.width, it.height) }

    private fun pairedSideField(main: PdfTextBox, field: PdfTextBox, font: Float): Boolean {
        val tolerance = max(.6f, font * .25f)
        return abs(main.y - field.y) <= tolerance && abs(main.bottom - field.bottom) <= tolerance &&
            min(abs(field.right - main.x), abs(main.right - field.x)) <= tolerance &&
            (abs(main.width - field.width) <= max(.6f, main.width * .08f) ||
                abs(main.width - field.width * 2f) <= max(.6f, main.width * .08f))
    }

    private fun corrected(box: PdfTextBox, page: PdfPageText, rotation: Int): PdfTextBox {
        if (rotation == 0) return box
        val a = EplanParsing.rotate(box.x, box.y, page.width, page.height, rotation)
        val b = EplanParsing.rotate(box.right, box.bottom, page.width, page.height, rotation)
        val anchor = EplanParsing.rotate(box.baselineX, box.baselineY, page.width, page.height, rotation)
        return box.copy(x = min(a.first, b.first), y = min(a.second, b.second),
            width = abs(a.first - b.first), height = abs(a.second - b.second),
            baselineX = anchor.first, baselineY = anchor.second, angle = (box.angle + rotation) % 360)
    }

    private fun unrotated(page: PdfPageText, boxes: List<PdfTextBox>) = page.copy(
        width = if (page.rotation % 180 == 0) page.width else page.height,
        height = if (page.rotation % 180 == 0) page.height else page.width,
        boxes = boxes, layoutBoxes = null, rotation = 0,
    )

    /**
     * EPLAN repeats a component's property rectangle at the ID, model and description columns.
     * Matching the rectangle's top/bottom/width is stronger evidence than a distance to text.
     * A narrow terminal strip has vertical main IDs and overlapping long caption hitboxes; those
     * retain the separate insertion-anchor matcher instead of being interpreted as a wide column.
     */
    private fun propertyFieldOwners(
        page: PdfPageText,
        devices: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Map<PdfTextBox, PdfTextBox> {
        if (areas.isEmpty()) return emptyMap()
        val usable = areas.filter { it.width > 0f && it.height > 0f && it.width < page.width * .25f && it.height < page.height * .35f }
            .distinctBy { listOf(it.x, it.y, it.width, it.height) }
        val mainFields = devices.filter { angleDifference(it.angle, 0) <= 3 }
            .flatMap { device ->
                checkCancelled()
                usable.filter { inside(Point(device.cx, device.cy), it, .3f) &&
                    device.baselineY >= it.y - .5f && device.baselineY <= it.bottom + .5f }
                    .map { device to it }
            }
        if (mainFields.isEmpty()) return emptyMap()
        val result = linkedMapOf<PdfTextBox, PdfTextBox>()
        for (text in page.boxes) {
            checkCancelled()
            if (!text.blue || text.cy > page.height * .915f || text.cy < page.height * .035f) continue
            // Small symbol/pin hitboxes may overlap a caption. First retain only rectangles that
            // can be paired with a main field; choosing one rectangle before pairing loses P24
            // and the second contactor-control label on dense terminal strips.
            val fields = usable.filter { field ->
                text.baselineY >= field.y - .5f && text.baselineY <= field.bottom + .5f &&
                    (inside(Point(text.cx, text.cy), field, .3f) || inside(Point(text.baselineX, text.baselineY), field, .3f))
            }
            val matching = fields.flatMap { field -> mainFields.mapNotNull { (device, main) ->
                val tolerance = max(.8f, min(device.fontSize, text.fontSize) * .2f)
                val sameVerticalExtent = abs(field.y - main.y) <= tolerance && abs(field.bottom - main.bottom) <= tolerance
                val sameWidth = abs(field.width - main.width) <= max(1f, main.width * .08f)
                // Some properties span the middle two cells of EPLAN's 1:2:1 blocks. A matching
                // final cell is required, so a broad unrelated rectangle cannot become a field.
                val middleToRight = abs(field.x - main.right) <= tolerance &&
                    usable.any { last -> abs(last.x - field.right) <= tolerance &&
                        abs(last.y - main.y) <= tolerance && abs(last.bottom - main.bottom) <= tolerance &&
                        abs(last.width - main.width) <= max(1f, main.width * .08f) }
                val middleToLeft = abs(field.right - main.x) <= tolerance &&
                    usable.any { last -> abs(last.right - field.x) <= tolerance &&
                        abs(last.y - main.y) <= tolerance && abs(last.bottom - main.bottom) <= tolerance &&
                        abs(last.width - main.width) <= max(1f, main.width * .08f) }
                val middleDoubleCell = abs(field.width - main.width * 2f) <= max(1f, main.width * .12f) &&
                    (middleToRight || middleToLeft)
                val separateField = field.x >= main.right - tolerance || field.right <= main.x + tolerance
                val gap = min(abs(field.x - main.right), abs(main.x - field.right))
                if (sameVerticalExtent && separateField && gap < page.width * .18f &&
                    (sameWidth || middleDoubleCell)) Triple(device, main, field) else null
            } }
            fun textBottom(match: Triple<PdfTextBox, PdfTextBox, PdfTextBox>) = abs(match.third.bottom - text.baselineY - text.fontSize * .3f)
            fun mainBottom(match: Triple<PdfTextBox, PdfTextBox, PdfTextBox>) = abs(match.second.bottom - match.first.baselineY - match.first.fontSize * .3f)
            fun gap(match: Triple<PdfTextBox, PdfTextBox, PdfTextBox>) = min(abs(match.third.x - match.second.right), abs(match.second.x - match.third.right))
            // Both sides can contain text/pin hitboxes, and neighboring socket blocks overlap.
            // Pair before choosing, then use BOTH baselines to select the physical row.
            val ordered = matching.sortedWith(compareBy<Triple<PdfTextBox, PdfTextBox, PdfTextBox>> { textBottom(it) }
                .thenBy { mainBottom(it) }.thenBy { gap(it) })
            val bestField = ordered.firstOrNull() ?: continue
            if (ordered.any { it.first != bestField.first && abs(textBottom(it) - textBottom(bestField)) < .3f &&
                    abs(mainBottom(it) - mainBottom(bestField)) < .3f && abs(gap(it) - gap(bestField)) < .3f }) continue
            val owner = bestField.first
            if (owner != text) result[text] = owner
        }
        return result
    }

    /** A confirmed function cell may legitimately contain English; arbitrary model text cannot. */
    fun confirmedFunction(text: String): Boolean {
        val value = text.trim()
        if (value.isBlank() || value in setOf("=", "—", "-", "/", "无", "未标注")) return false
        if (EplanParsing.deviceId(value) != null) return false
        if (Regex("^(?:设备标识符|功能文本|类型号|供应商|制造商)$").matches(value)) return false
        if (Regex("^[=+&]|^/[0-9]|页描述|项目描述|总页").containsMatchIn(value)) return false
        return !Regex("^[0-9. ×xX*+\\-/():]+(?:mm|cm|V|A|W|kW)?$", RegexOption.IGNORE_CASE).matches(value)
    }

    /**
     * Infer each four-column panel from its header and aligned device column. Continuation panels
     * inherit the column offsets only when several device rows independently support that column.
     * Every function lookup is bounded on BOTH sides and by its own panel's adjacent row midpoints.
     */
    fun functionTable(page: PdfPageText, checkCancelled: () -> Unit = {}): Map<String, List<AbFunctionEvidence>> {
        checkCancelled()
        val templates = page.boxes.filter { it.text.trim() == "功能文本" && it.cy < page.height * .4f }
            .mapNotNull { function ->
                checkCancelled()
                val headers = page.boxes.filter { abs(it.cy - function.cy) < max(it.fontSize, function.fontSize) * 1.2f && it.cx < function.cx }
                val supplier = headers.filter { it.text.trim() in setOf("供应商", "制造商") }.maxByOrNull { it.cx } ?: return@mapNotNull null
                val model = headers.filter { it.cx < supplier.cx && it.text.trim() in setOf("类型号", "型号", "类型编号") }.maxByOrNull { it.cx } ?: return@mapNotNull null
                val device = headers.filter { it.cx < model.cx && it.text.contains("设备标识符") }.maxByOrNull { it.cx } ?: return@mapNotNull null
                TableTemplate(device, model, supplier, function)
            }.sortedBy { it.device.cx }
        if (templates.isEmpty()) return emptyMap()
        // A headerless continuation may start ABOVE the left panel's header row (India p257,
        // 663 p166). Its own first device row defines its top; do not crop all panels at one header.
        val top = max(page.height * .035f, templates.minOf { it.device.y - it.device.fontSize * 2f })
        val candidates = page.boxes.filter { it.cy > top && it.cy < page.height * .915f }
        val ids = candidates.filter { EplanParsing.deviceId(it.text) != null }
        val clusters = mutableListOf<MutableList<PdfTextBox>>()
        for (id in ids.sortedBy { it.cx }) {
            checkCancelled()
            val cluster = clusters.lastOrNull()
            if (cluster != null && abs(id.cx - cluster.first().cx) <= max(8f, id.fontSize * 1.8f)) cluster += id
            else clusters += mutableListOf(id)
        }
        val panels = clusters.mapNotNull { rows ->
            checkCancelled()
            val center = rows.map { it.cx }.sorted()[rows.size / 2]
            val own = templates.minByOrNull { abs(it.device.cx - center) }!!
            val explicit = abs(own.device.cx - center) < max(12f, own.device.fontSize * 2f)
            // An ID-shaped model elsewhere in a row cannot manufacture an extra panel.
            if (!explicit && (rows.size < 2 || center < templates.first().function.right + own.device.fontSize * 2f)) return@mapNotNull null
            val template = if (explicit) own else templates.lastOrNull { it.device.cx < center } ?: return@mapNotNull null
            Panel(center, template, uniquePositions(rows, checkCancelled).sortedBy { it.cy })
        }.sortedBy { it.center }
        val result = linkedMapOf<String, MutableList<AbFunctionEvidence>>()
        for ((panelIndex, panel) in panels.withIndex()) {
            checkCancelled()
            val template = panel.template
            val shift = panel.center - template.device.cx
            val explicit = abs(shift) < max(12f, template.device.fontSize * 2f)
            val panelTop = if (explicit) template.device.bottom else top
            val left = (template.supplier.cx + template.function.cx) / 2f + shift
            val next = panels.getOrNull(panelIndex + 1)
            val right = next?.let { it.center - (it.template.model.cx - it.template.device.cx) / 2f }
                ?: page.width * .985f
            if (left >= right) continue
            val functionBoxes = candidates.filter { it.x >= left - it.fontSize * .3f && it.cx < right && it.right <= right + it.fontSize }
            for ((rowIndex, device) in panel.rows.withIndex()) {
                checkCancelled()
                val previous = panel.rows.getOrNull(rowIndex - 1)
                val following = panel.rows.getOrNull(rowIndex + 1)
                val rowTop = previous?.let { (it.cy + device.cy) / 2f } ?: max(panelTop, device.cy - max(device.fontSize * 2f, following?.let { (it.cy - device.cy) / 2f } ?: 0f))
                val rowBottom = following?.let { (it.cy + device.cy) / 2f }
                    ?: min(page.height * .915f, device.cy + max(device.fontSize * 2f, previous?.let { (device.cy - it.cy) / 2f } ?: 0f))
                val values = functionBoxes.filter { it.cy >= rowTop && it.cy < rowBottom && confirmedFunction(it.text) }
                if (values.isEmpty()) continue
                val merged = mergeCell(values, page)
                val id = EplanParsing.deviceId(device.text) ?: continue
                result.getOrPut(id) { mutableListOf() }.addAll(merged)
            }
        }
        return result
    }

    /** Remove only a second printing at the same physical position, not another installed instance. */
    private fun uniquePositions(boxes: List<PdfTextBox>, checkCancelled: () -> Unit = {}): List<PdfTextBox> {
        val result = mutableListOf<PdfTextBox>()
        for (box in boxes.sortedWith(compareBy<PdfTextBox> { it.x }.thenBy { it.y })) {
            checkCancelled()
            if (result.none { it.text == box.text && abs(it.baselineX - box.baselineX) < .3f && abs(it.baselineY - box.baselineY) < .3f && angleDifference(it.angle, box.angle) < 2 }) result += box
        }
        return result
    }

    /** Weak or mixed text directions leave the physical installation upright. */
    fun inferRotation(page: PdfPageText, devices: List<PdfTextBox>): Pair<Int, Boolean> {
        if (devices.isEmpty()) return 0 to false
        val margin = devices.maxOf { it.fontSize }.coerceAtLeast(3f) * 15f
        val bounds = bounds(devices)
        val dimensions = page.boxes.filter { box ->
            box.cy < page.height * .91f && inside(Point(box.cx, box.cy), bounds, margin) &&
                Regex("(?:mm|毫米)$|^[0-9]+[×*][0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(box.text.trim())
        }
        if (dimensions.size < 3) return 0 to false
        val bins = dimensions.groupingBy { quadrant(it.angle) }.eachCount()
        val majority = bins.maxByOrNull { it.value } ?: return 0 to false
        if (majority.value < dimensions.size * .85f) return 0 to false
        val direction = majority.key
        if (direction == 0) return 0 to true
        // Vertical legends are common on an upright board. Require independent body dimensions
        // AND nearly uniform matching device text before applying an automatic whole-region turn.
        val supporting = devices.count { quadrant(it.angle) == direction }
        if (supporting < devices.size * .85f) return 0 to false
        return ((360 - direction) % 360) to true
    }

    fun installedPositions(
        page: PdfPageText,
        devices: List<PdfTextBox>,
        rotationOverride: Int? = null,
        checkCancelled: () -> Unit = {},
    ): List<AbInstalledPosition> {
        checkCancelled()
        val distinct = uniquePositions(devices, checkCancelled)
        if (distinct.isEmpty()) return emptyList()
        val regions = splitRegions(page, distinct, checkCancelled)
        return regions.flatMapIndexed { region, boxes ->
            checkCancelled()
            val rotation = rotationOverride ?: inferRotation(page, boxes).first
            // Vertical combined IDs are longer than their neighbors. Their printed center moves
            // up/down although the physical label anchor remains on the same installed rail.
            val anchors = boxes.associateBy { it.copy(x = it.cx, y = it.baselineY, width = 0f, height = 0f) }
            EplanParsing.readingOrder(anchors.keys.toList(), page.width, page.height, rotation)
                .map { AbInstalledPosition(anchors.getValue(it), region, rotation) }
        }
    }

    /** Split only a large empty gutter corroborated by separate installation headings. */
    private fun splitRegions(page: PdfPageText, devices: List<PdfTextBox>, checkCancelled: () -> Unit): List<List<PdfTextBox>> {
        val headings = page.boxes.filter { it.cy < page.height * .9f && Regex("安装板|柜门|门板|底板|侧板|安装区域").containsMatchIn(it.text) && EplanParsing.deviceId(it.text) == null }
        if (headings.size < 2 || devices.size < 4) return listOf(devices)
        val ordered = devices.sortedBy { it.cx }
        var previousRight = ordered.first().right
        val splits = (1 until ordered.size).map { index ->
            checkCancelled()
            val gap = ordered[index].x - previousRight
            previousRight = max(previousRight, ordered[index].right)
            index to gap
        }
            .filter { (index, gap) -> index >= 2 && ordered.size - index >= 2 && gap > page.width * .12f }
            .sortedByDescending { it.second }
        for ((index, _) in splits) {
            checkCancelled()
            val left = ordered.take(index)
            val right = ordered.drop(index)
            val split = (left.maxOf { it.right } + right.minOf { it.x }) / 2f
            if (headings.none { it.cx < split } || headings.none { it.cx > split }) continue
            return listOf(left, right).sortedWith(compareBy<List<PdfTextBox>> { it.minOf { b -> b.y } / (devices.maxOf { b -> b.fontSize } * 3f) }.thenBy { it.minOf { b -> b.x } })
        }
        return listOf(devices)
    }

    /**
     * Assign a caption once to a physical instance. Diagonal captions are compared from their
     * baseline insertion anchors, not their overlapping axis-aligned boxes. Optional bounds are reliable
     * device bodies, not a page-sized destination or a tiny text-only hyperlink hitbox.
     */
    fun associateFunctions(
        page: PdfPageText,
        devices: List<PdfTextBox>,
        owners: Map<PdfTextBox, PdfTextBox> = emptyMap(),
        checkCancelled: () -> Unit = {},
        linkAreas: List<PdfTextBox> = emptyList(),
    ): Map<PdfTextBox, List<AbFunctionEvidence>> {
        checkCancelled()
        if (devices.isEmpty()) return emptyMap()
        if (page.rotation != 0) {
            // Interpret EPLAN property structure in the original drawing coordinates. A PDF
            // viewer's /Rotate must not turn a vertical module into a horizontal table column.
            val turn = (360 - page.rotation) % 360
            val transformed = (page.boxes + devices + owners.keys + owners.values + linkAreas).distinct()
                .associateWith { corrected(it, page, turn) }
            val originals = transformed.entries.associate { it.value to it.key }
            val view = unrotated(page, page.boxes.map { transformed.getValue(it) })
            val mapped = associateFunctions(view, devices.map { transformed.getValue(it) },
                owners.entries.associate { transformed.getValue(it.key) to transformed.getValue(it.value) },
                checkCancelled, linkAreas.map { transformed.getValue(it) })
            return mapped.entries.associate { (owner, functions) -> originals.getValue(owner) to functions.map {
                it.copy(box = originals[it.box] ?: corrected(it.box, view, page.rotation))
            } }
        }
        val assigned = linkedMapOf<PdfTextBox, MutableList<PdfTextBox>>()
        val body = page.boxes.filter { it.cy > page.height * .035f && it.cy < page.height * .91f }
        val fields = propertyFieldOwners(page, devices, linkAreas, checkCancelled)
        val fuses = nativeFusePropertyOwners(page, devices, linkAreas, checkCancelled)
        val nativeFields = nativeComponentPropertyOwners(page, devices, linkAreas, checkCancelled)
        val deviceSet = devices.toHashSet()
        val terminalBanks = nativeTerminalBankOwners(page,
            body.filter { it.blue && it !in deviceSet && confirmedFunction(it.text) }, devices, linkAreas, checkCancelled)
        val candidates = body.filter { box -> box.blue && box !in deviceSet &&
            (EplanParsing.isFunction(box.text) || fuses[box] != null || nativeFields[box] != null || terminalBanks[box] != null || (fields[box] != null &&
                (confirmedFunction(box.text) || EplanParsing.deviceId(box.text) != null))) }
        val channels = channelOwners(page, candidates, devices, linkAreas, checkCancelled)
        val phases = completeTerminalPhaseOwners(page, candidates, devices, linkAreas, checkCancelled)
        val vertical = verticalInstallationOwners(page, candidates, devices, linkAreas, checkCancelled)
        for (function in uniquePositions(candidates, checkCancelled)) {
            checkCancelled()
            val fieldOwner = fuses[function] ?: channels[function] ?: phases[function] ?: terminalBanks[function] ?: nativeFields[function]
                ?: vertical[function] ?: fields[function]
            if (fieldOwner != null) {
                assigned.getOrPut(fieldOwner) { mutableListOf() }.add(function)
                continue
            }
            val scored = devices.map { device -> device to associationScore(device, function, owners[device]) }.sortedBy { it.second }
            val best = scored.firstOrNull() ?: continue
            val limit = max(35f, max(function.fontSize, best.first.fontSize) * 13f)
            if (best.second > limit) continue
            if (scored.size > 1 && scored[1].second - best.second < max(.35f, function.fontSize * .15f)) continue
            assigned.getOrPut(best.first) { mutableListOf() }.add(function)
        }
        extendTerminalPhaseGroups(candidates, devices, fields + vertical + nativeFields + phases, assigned, page, checkCancelled)
        // Parenthetical specification lines may be filtered by the generic function predicate;
        // attach them only to an already identified nearby function, never as a fresh label.
        val candidateSet = candidates.toHashSet()
        val continuations = body.filter { box -> box.blue && box !in candidateSet && isContinuation(box.text) }
        for (continuation in continuations) {
            checkCancelled()
            val matches = assigned.flatMap { (device, values) -> values.mapNotNull { previous ->
                if (canContinue(previous, continuation)) Triple(device, previous, baselineDistance(previous, continuation)) else null
            } }.sortedBy { it.third }
            val best = matches.firstOrNull() ?: continue
            if (matches.size > 1 && matches[1].third - best.third < .3f) continue
            assigned.getValue(best.first).add(continuation)
        }
        return assigned.mapValues { (device, boxes) ->
            checkCancelled()
            // Proven pin groups have their own independent captions. Unequal diagonal text
            // lengths must not reorder their common insertion baseline or merge their cells.
            if (boxes.all { terminalBanks[it] == device }) boxes.sortedBy { it.sourceSequence }
                .map { AbFunctionEvidence(it.text, it, page.page) }
            else mergeLayout(boxes, page, checkCancelled)
        }
    }

    /**
     * A tall body can put its caption beside a different rail's IDs. A native ID/model/caption
     * triplet plus the full matching 1:2:1 property block is stronger than that visual proximity.
     * Both native orderings (ID/model/function and ID/function/model) are used by EPLAN.
     */
    private fun nativeComponentPropertyOwners(
        page: PdfPageText,
        devices: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Map<PdfTextBox, PdfTextBox> {
        if (areas.isEmpty()) return emptyMap()
        fun native(box: PdfTextBox) = box.sourceObject >= 0 && box.sourceRun >= 0 &&
            box.sourceSequence >= 0 && box.sourceEndSequence >= box.sourceSequence
        val ordered = page.boxes.filter(::native).sortedBy { it.sourceSequence }
        val deviceSet = devices.toHashSet()
        val fields = installationFields(page, areas)
        val result = linkedMapOf<PdfTextBox, PdfTextBox>()
        val rectCache = mutableMapOf<Pair<Int, Float>, List<PdfTextBox>>()
        for (device in devices) {
            checkCancelled()
            if (!native(device) || angleDifference(device.angle, quadrant(device.angle)) > 3) continue
            val next = ordered.asSequence().filter { it.sourceSequence > device.sourceEndSequence }.take(2).toList()
            if (next.size != 2 || next.any { it in deviceSet } || next[0].sourceEndSequence >= next[1].sourceSequence ||
                next[0].sourceRun <= device.sourceRun || next[1].sourceRun <= next[0].sourceRun) continue
            val caption = next.singleOrNull { it.blue } ?: continue
            val model = next.singleOrNull { !it.blue } ?: continue
            if (!confirmedFunction(caption.text) || EplanParsing.deviceId(caption.text, requireLeader = false) != null ||
                channelNumber(caption.text) != null ||
                !Regex("[A-Za-z][A-Za-z0-9_./-]*[0-9]").containsMatchIn(model.text) ||
                angleDifference(model.angle, caption.angle) > 3 ||
                caption.fontSize / model.fontSize.coerceAtLeast(.1f) !in .8f..1.25f) continue
            if (EplanParsing.deviceId(device.text) == null && isUnmarkedNativeTallBody(
                    page, device, caption, model, devices.filter { it != device }, areas, checkCancelled)) {
                result[caption] = device
                continue
            }
            val rotation = (270 - quadrant(device.angle) + 360) % 360
            val id = corrected(device, page, rotation)
            val function = corrected(caption, page, rotation)
            val type = corrected(model, page, rotation)
            val tolerance = max(.6f, id.fontSize * .2f)
            val rects = rectCache.getOrPut(rotation to tolerance) {
                recoverMiddlePropertyCells(fields.map { corrected(it, page, rotation) }, tolerance, checkCancelled)
            }
            val heads = rects.filter { field ->
                field.height >= id.height * 1.6f && abs(field.y - id.y) <= tolerance &&
                    id.baselineX >= field.x - .3f && id.baselineX <= field.right + .3f
            }
            var confirmed = false
            for (main in heads) {
                checkCancelled()
                val peers = rects.filter { other ->
                    abs(other.y - main.y) <= tolerance && abs(other.bottom - main.bottom) <= tolerance &&
                        other.x >= main.x - main.width * 3f - tolerance &&
                        other.right <= main.right + main.width * 3f + tolerance
                }
                for (left in peers) {
                    val middles = peers.filter { middle ->
                        abs(middle.x - left.right) <= tolerance &&
                            abs(middle.width - left.width * 2f) <= max(.6f, left.width * .12f)
                    }
                    for (middle in middles) for (right in peers) {
                        checkCancelled()
                        if (main != left && main != middle && main != right) continue
                        if (abs(right.x - middle.right) > tolerance ||
                            abs(right.width - left.width) > max(.6f, left.width * .08f)) continue
                        val container = PdfTextBox("", left.x, main.y, right.right - left.x, main.height)
                        if (function.baselineY <= id.bottom + max(.6f, function.fontSize * .4f) ||
                            !inside(Point(function.baselineX, function.baselineY), container, .3f) ||
                            !inside(Point(type.baselineX, type.baselineY), container, .3f)) continue
                        confirmed = true
                        break
                    }
                    if (confirmed) break
                }
                if (confirmed) break
            }
            if (confirmed) result[caption] = device
        }
        return result
    }

    /** Two equal side cells can prove the missing double-width middle cell, never a lone box. */
    private fun recoverMiddlePropertyCells(
        rects: List<PdfTextBox>, tolerance: Float, checkCancelled: () -> Unit,
    ): List<PdfTextBox> {
        val recovered = mutableListOf<PdfTextBox>()
        for (left in rects) {
            checkCancelled()
            for (right in rects) {
                val gap = right.x - left.right
                if (gap <= 0f || abs(left.y - right.y) > tolerance || abs(left.bottom - right.bottom) > tolerance ||
                    abs(left.width - right.width) > max(.6f, left.width * .08f) ||
                    abs(gap - left.width * 2f) > max(.6f, left.width * .12f)) continue
                recovered += PdfTextBox("", left.right, left.y, gap, left.height)
            }
        }
        return (rects + recovered).distinctBy { listOf(it.x, it.y, it.width, it.height) }
    }

    /**
     * One XT main can label one pin or a wide bank: later captions can exceed its ID hitbox.
     * Keep that bank together only when the native sequence, ascending printed pins and shared
     * insertion baseline agree, and the first caption/model belong to its 1:2:1 property block.
     * Reading ends at the next actual main ID; geometry never borrows text from another row.
     */
    private fun nativeTerminalBankOwners(
        page: PdfPageText,
        candidates: List<PdfTextBox>,
        devices: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Map<PdfTextBox, PdfTextBox> {
        if (areas.isEmpty()) return emptyMap()
        fun native(box: PdfTextBox) = box.sourceObject >= 0 && box.sourceRun >= 0 &&
            box.sourceSequence >= 0 && box.sourceEndSequence >= box.sourceSequence
        val ordered = page.boxes.filter(::native).filter { it.text.isNotBlank() }.sortedBy { it.sourceSequence }
        val index = ordered.withIndex().associate { it.value to it.index }
        val deviceSet = devices.toHashSet()
        val candidateSet = candidates.toHashSet()
        val fields = installationFields(page, areas)
        val result = linkedMapOf<PdfTextBox, PdfTextBox>()
        val rectCache = mutableMapOf<Pair<Int, Float>, List<PdfTextBox>>()
        for (device in devices) {
            checkCancelled()
            if (EplanParsing.deviceId(device.text, requireLeader = false)?.let(EplanParsing::isXt) != true ||
                angleDifference(device.angle, quadrant(device.angle)) > 3) continue
            val start = index[device] ?: continue
            val segment = ordered.asSequence().drop(start + 1).takeWhile {
                it !in deviceSet && !(it.blue && EplanParsing.deviceId(it.text) != null)
            }.toList()
            if (segment.size < 3 || segment.zipWithNext().any { (a, b) ->
                    b.sourceSequence <= a.sourceEndSequence || b.sourceRun < a.sourceRun }) continue
            val rotation = (270 - quadrant(device.angle) + 360) % 360
            val id = corrected(device, page, rotation)
            val boxes = segment.associateWith { corrected(it, page, rotation) }
            fun pin(box: PdfTextBox): Int? = if (box.blue && Regex("^[0-9]{1,3}$").matches(box.text.trim()) &&
                angleDifference(boxes.getValue(box).angle, 0) <= 3) box.text.trim().toIntOrNull()?.takeIf { it > 0 } else null
            val pins = segment.filter { pin(it) != null }
            if (pins.isEmpty() || pin(segment.first()) != 1) continue
            val pinRow = boxes.getValue(pins.first())
            if (pins.any { abs(boxes.getValue(it).baselineY - pinRow.baselineY) > max(.35f, pinRow.fontSize * .3f) } ||
                pins.sortedBy { boxes.getValue(it).baselineX }.zipWithNext().any { (a, b) -> pin(b)!! <= pin(a)!! ||
                    boxes.getValue(b).baselineX <= boxes.getValue(a).baselineX }) continue
            val captions = segment.filter { it in candidateSet }
            if (captions.isEmpty()) continue
            if (captions.zipWithNext().any { (a, b) ->
                    boxes.getValue(b).baselineX <= boxes.getValue(a).baselineX }) continue
            val first = captions.first()
            if (segment.indexOf(first) != 1) continue
            val seed = boxes.getValue(first)
            val model = segment.getOrNull(2) ?: continue
            val type = boxes.getValue(model)
            if (model.blue || !Regex("[A-Za-z][A-Za-z0-9_./-]*[0-9]").containsMatchIn(model.text) ||
                angleDifference(type.angle, seed.angle) > 3 || seed.fontSize / type.fontSize.coerceAtLeast(.1f) !in .8f..1.25f) continue
            if (captions.any { caption ->
                    val box = boxes.getValue(caption)
                    val preceding = segment.getOrNull(segment.indexOf(caption) - 1)
                    preceding == null || pin(preceding) == null ||
                        angleDifference(box.angle, seed.angle) > 3 ||
                        box.fontSize / seed.fontSize.coerceAtLeast(.1f) !in .95f..1.05f ||
                        abs(box.baselineY - seed.baselineY) > max(.35f, seed.fontSize * .25f) ||
                        abs(box.baselineX - boxes.getValue(preceding).baselineX) > max(.6f, seed.fontSize / .65f * 1.5f) ||
                        box.baselineY - boxes.getValue(preceding).baselineY !in 0f..(seed.fontSize / .65f * 2f)
                }) continue
            val tolerance = max(.6f, id.fontSize * .2f)
            // EPLAN can omit the middle body's hyperlink while both equal side cells remain.
            // Recover it only from that complete geometric pair, including for a single pin.
            val rects = rectCache.getOrPut(rotation to tolerance) {
                recoverMiddlePropertyCells(fields.map { corrected(it, page, rotation) }, tolerance, checkCancelled)
            }
            var proven = false
            for (middle in rects) {
                checkCancelled()
                if (middle.height < id.height * 1.6f || abs(middle.y - id.y) > tolerance ||
                    id.baselineX !in (middle.x - .3f)..(middle.right + .3f)) continue
                val left = rects.filter { abs(it.right - middle.x) <= tolerance &&
                    abs(it.y - middle.y) <= tolerance && abs(it.bottom - middle.bottom) <= tolerance &&
                    abs(it.width * 2f - middle.width) <= max(.6f, middle.width * .08f) }
                val right = rects.filter { abs(it.x - middle.right) <= tolerance &&
                    abs(it.y - middle.y) <= tolerance && abs(it.bottom - middle.bottom) <= tolerance &&
                    abs(it.width * 2f - middle.width) <= max(.6f, middle.width * .08f) }
                proven = left.any { l -> right.any { r ->
                    val block = PdfTextBox("", l.x, middle.y, r.right - l.x, middle.height)
                    seed.baselineY > id.bottom + seed.fontSize &&
                        inside(Point(seed.baselineX, seed.baselineY), block, .3f) &&
                        inside(Point(type.baselineX, type.baselineY), block, .3f)
                } }
                if (proven) break
            }
            if (proven) captions.forEach { result[it] = device }
        }
        return result
    }

    /**
     * Dense fuse banks print one native ID, its black current rating and its blue caption in
     * succession. Their overlapping property hitboxes cannot identify a unique neighbor by
     * distance. Require that complete native triplet AND its own common property rectangle.
     * This also admits short English functions (IO / ECT) only inside a proven function field.
     */
    private fun nativeFusePropertyOwners(
        page: PdfPageText,
        devices: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Map<PdfTextBox, PdfTextBox> {
        if (areas.isEmpty()) return emptyMap()
        fun native(box: PdfTextBox) = box.sourceObject >= 0 && box.sourceRun >= 0 &&
            box.sourceSequence >= 0 && box.sourceEndSequence >= box.sourceSequence
        val ordered = page.boxes.filter(::native).sortedBy { it.sourceSequence }
        val fields = installationFields(page, areas)
        val deviceSet = devices.toHashSet()
        val result = linkedMapOf<PdfTextBox, PdfTextBox>()
        val rotatedFields = mutableMapOf<Pair<Int, Float>, List<PdfTextBox>>()
        for (device in devices) {
            checkCancelled()
            if (!native(device) || EplanParsing.deviceId(device.text, requireLeader = false)
                    ?.let(EplanParsing::deviceCategory) != "FU") continue
            val next = ordered.asSequence().filter { it.sourceSequence > device.sourceEndSequence }.take(2).toList()
            if (next.size != 2) continue
            val current = next[0]
            val caption = next[1]
            if (!LayoutFuseRatings.isRating(current) || !caption.blue || caption in deviceSet ||
                !confirmedFunction(caption.text) ||
                current.sourceRun <= device.sourceRun || caption.sourceRun <= current.sourceRun ||
                current.sourceEndSequence >= caption.sourceSequence ||
                !LayoutFuseRatings.samePrintedLine(current, caption) ||
                (angleDifference(device.angle, caption.angle) > 3 && angleDifference(device.angle, caption.angle) !in 177..180) ||
                caption.fontSize / device.fontSize.coerceAtLeast(.1f) !in .85f..1.15f) continue
            // Some EPLAN exports print all three fields in the same direction; others print
            // the ID against its caption. Normalize the physical ID-to-caption direction.
            val target = if (angleDifference(device.angle, caption.angle) <= 3) 90 else 270
            val rotation = (target - quadrant(device.angle) + 360) % 360
            if (angleDifference(device.angle, quadrant(device.angle)) > 3) continue
            val id = corrected(device, page, rotation)
            val value = corrected(caption, page, rotation)
            val amp = corrected(current, page, rotation)
            val font = min(id.fontSize, value.fontSize).coerceAtLeast(.5f)
            val transverse = abs(id.baselineX - value.baselineX)
            if (value.baselineY <= id.baselineY || value.baselineY - id.baselineY > max(40f, font * 24f) ||
                transverse > max(2f, font * 1.5f)) continue
            val proofFields = rotatedFields.getOrPut(rotation to font) {
                recoverMiddlePropertyCells(fields.map { corrected(it, page, rotation) }, max(.6f, font * .2f), checkCancelled)
            }
            val common = (target == 90 && inlineFuseField(page, device, current, caption, fields, checkCancelled)) || proofFields.any { field ->
                field.height >= field.width * 2f && field.height >= id.height * 2f &&
                    inside(Point(id.baselineX, id.baselineY), field, .3f) &&
                    inside(Point(amp.baselineX, amp.baselineY), field, .3f) &&
                    inside(Point(value.baselineX, value.baselineY), field, .3f)
            }
            if (common) result[caption] = device
        }
        return result
    }

    /** Same-direction fuse fields may be split into ID/body/specification cells (1:2:1).
     * The body link can be absent; equal outer cells plus the native triplet still locate it.
     * Caption and current may overlap at the body/specification boundary in the source PDF.
     */
    private fun inlineFuseField(
        page: PdfPageText, device: PdfTextBox, current: PdfTextBox, caption: PdfTextBox,
        fields: List<PdfTextBox>, checkCancelled: () -> Unit,
    ): Boolean {
        val turn = (360 - quadrant(device.angle)) % 360
        val id = corrected(device, page, turn)
        val amp = corrected(current, page, turn)
        val function = corrected(caption, page, turn)
        val rects = fields.map { corrected(it, page, turn) }
        val tolerance = max(.6f, id.fontSize * .25f)
        if (abs(id.baselineY - function.baselineY) > tolerance) return false
        for (head in rects) {
            checkCancelled()
            if (!inside(Point(id.baselineX, id.baselineY), head, .3f) ||
                abs(id.baselineX - head.x) > tolerance || id.right > head.right + tolerance) continue
            for (tail in rects) {
                if (abs(tail.y - head.y) > tolerance || abs(tail.bottom - head.bottom) > tolerance ||
                    abs(tail.width - head.width) > tolerance ||
                    abs(tail.x - head.right - head.width * 2f) > tolerance ||
                    !inside(Point(amp.baselineX, amp.baselineY), tail, .3f)) continue
                val bodyAndTail = PdfTextBox("", head.right, head.y, tail.right - head.right, head.height)
                if (inside(Point(function.baselineX, function.baselineY), bodyAndTail, .3f)) return true
            }
        }
        return false
    }

    /**
     * Some terminal banks omit the middle hyperlink but retain the two side cells. Recover
     * that 1:2:1 block only for a complete, independently printed four-phase family whose
     * native output follows its sole main XT identifier without another function or ID.
     */
    private fun completeTerminalPhaseOwners(
        page: PdfPageText,
        candidates: List<PdfTextBox>,
        devices: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Map<PdfTextBox, PdfTextBox> {
        if (areas.isEmpty()) return emptyMap()
        val phase = Regex("^(.+)[-－](N1|L[123])$", RegexOption.IGNORE_CASE)
        val required = setOf("N1", "L1", "L2", "L3")
        fun native(box: PdfTextBox) = box.sourceObject >= 0 && box.sourceRun >= 0 &&
            box.sourceSequence >= 0 && box.sourceEndSequence >= box.sourceSequence
        data class Owner(val device: PdfTextBox, val container: PdfTextBox)
        val matches = linkedMapOf<PdfTextBox, MutableSet<Owner>>()
        val terminals = devices.filter { device ->
            native(device) && EplanParsing.deviceId(device.text, requireLeader = false)?.let(EplanParsing::isXt) == true &&
                angleDifference(device.angle, quadrant(device.angle)) <= 3
        }
        for ((direction, directional) in terminals.groupBy { quadrant(it.angle) }) {
            checkCancelled()
            val rotation = (270 - direction + 360) % 360
            val rects = installationFields(page, areas).map { corrected(it, page, rotation) }
            val allMains = devices.associateWith { corrected(it, page, rotation) }
            val captionBoxes = candidates.filter { native(it) && phase.matches(it.text.trim()) }
                .associateWith { corrected(it, page, rotation) }
            val families = captionBoxes.keys.groupBy { box ->
                phase.matchEntire(box.text.trim())!!.groupValues[1] to captionBoxes.getValue(box).angle
            }
            for (family in families.values) {
                checkCancelled()
                val rows = mutableListOf<MutableList<PdfTextBox>>()
                for (caption in family.sortedBy { captionBoxes.getValue(it).baselineY }) {
                    val box = captionBoxes.getValue(caption)
                    val row = rows.lastOrNull()
                    val seed = row?.firstOrNull()?.let { captionBoxes.getValue(it) }
                    if (row != null && seed != null &&
                        abs(box.baselineY - seed.baselineY) <= max(.35f, min(box.fontSize, seed.fontSize) * .25f) &&
                        box.fontSize / seed.fontSize.coerceAtLeast(.1f) in .95f..1.05f) row += caption
                    else rows += mutableListOf(caption)
                }
                for (row in rows) {
                    val banks = mutableListOf<MutableList<PdfTextBox>>()
                    for (caption in row.sortedBy { captionBoxes.getValue(it).baselineX }) {
                        val bank = banks.lastOrNull()
                        val previous = bank?.lastOrNull()?.let { captionBoxes.getValue(it) }
                        val box = captionBoxes.getValue(caption)
                        if (bank != null && previous != null && box.baselineX - previous.baselineX <=
                            max(.6f, min(box.fontSize, previous.fontSize) / .65f * 6f)) bank += caption
                        else banks += mutableListOf(caption)
                    }
                    for (bank in banks) {
                        checkCancelled()
                        if (bank.size != 4 || bank.map { phase.matchEntire(it.text.trim())!!.groupValues[2].uppercase() }.toSet() != required) continue
                        if (bank.maxOf { it.fontSize } / bank.minOf { it.fontSize }.coerceAtLeast(.1f) > 1.05f) continue
                        val sequence = bank.sortedBy { it.sourceSequence }
                        if (sequence.zipWithNext().any { (a, b) -> b.sourceSequence <= a.sourceEndSequence || b.sourceRun <= a.sourceRun }) continue
                        val bankEnd = sequence.last().sourceEndSequence
                        val bankSet = bank.toHashSet()
                        // PositionedStripper stores .65 em as the glyph thickness. Restore one
                        // nominal em here; this bound applies only after every phase-bank proof.
                        val font = bank.minOf { captionBoxes.getValue(it).fontSize } / .65f
                        for (device in directional) {
                            checkCancelled()
                            if (device.sourceEndSequence >= sequence.first().sourceSequence || device.sourceRun >= sequence.first().sourceRun ||
                                devices.any { other -> other != device && (!native(other) ||
                                    other.sourceSequence in (device.sourceEndSequence + 1)..bankEnd) } ||
                                candidates.any { other -> other !in bankSet && (!native(other) ||
                                    other.sourceSequence in (device.sourceEndSequence + 1)..bankEnd) }) continue
                            val id = allMains.getValue(device)
                            val tolerance = max(.6f, id.fontSize * .2f)
                            val sides = rects.filter { field ->
                                abs(field.y - id.y) <= tolerance && field.height >= id.height * 1.6f &&
                                    field.height >= field.width * 4f && field.width >= id.width * .7f &&
                                    field.width <= max(id.width * 2f, id.fontSize * 2f)
                            }
                            for (left in sides) for (right in sides) {
                                checkCancelled()
                                if (right.x <= left.right || abs(left.width - right.width) > max(.6f, left.width * .08f) ||
                                    abs(left.y - right.y) > tolerance || abs(left.bottom - right.bottom) > tolerance ||
                                    abs(right.x - left.right - left.width * 2f) > max(.6f, left.width * .12f) ||
                                    id.x < left.right - .3f || id.x > right.x + .3f) continue
                                val container = PdfTextBox("", left.x, left.y, right.right - left.x,
                                    max(left.bottom, right.bottom) - left.y)
                                if (allMains.any { (other, box) -> other != device && inside(Point(box.x, box.y), container, .3f) } ||
                                    bank.any { caption ->
                                        val box = captionBoxes.getValue(caption)
                                        box.baselineY <= id.bottom + max(.6f, box.fontSize * .4f) ||
                                            !inside(Point(box.baselineX, box.baselineY), container, font)
                                    }) continue
                                val owner = Owner(device, container)
                                bank.forEach { matches.getOrPut(it) { linkedSetOf() }.add(owner) }
                            }
                        }
                    }
                }
            }
        }
        return matches.mapNotNull { (caption, owners) -> owners.singleOrNull()?.let { caption to it.device } }.toMap()
    }

    /**
     * A tall 1:2:1 property block can print its main ID at the head and its slanted caption
     * near the foot of the SAME narrow cell. Text centers and the far end of the caption
     * cross neighboring cells; only the native insertion anchor proves this ownership.
     */
    private fun verticalInstallationOwners(
        page: PdfPageText,
        candidates: List<PdfTextBox>,
        devices: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Map<PdfTextBox, PdfTextBox> {
        if (areas.isEmpty()) return emptyMap()
        val matches = linkedMapOf<PdfTextBox, MutableSet<PdfTextBox>>()
        for ((direction, directional) in devices.groupBy { quadrant(it.angle) }) {
            checkCancelled()
            val rotation = (270 - direction + 360) % 360
            val rects = installationFields(page, areas).map { corrected(it, page, rotation) }
            val allMains = devices.associateWith { corrected(it, page, rotation) }
            val mains = directional.filter { angleDifference(it.angle, direction) <= 3 }
                .associateWith { allMains.getValue(it) }
            val captions = candidates.associateWith { corrected(it, page, rotation) }
            for ((device, id) in mains) {
                checkCancelled()
                val tolerance = max(.6f, id.fontSize * .2f)
                fun atHead(other: PdfTextBox, field: PdfTextBox) =
                    abs(other.y - field.y) <= tolerance &&
                        inside(Point(other.x, other.y), field, .3f)
                for (main in rects) {
                    checkCancelled()
                    if (main.height < main.width * 4f || main.height < id.height * 1.6f ||
                        main.width < id.width * .7f || main.width > max(id.width * 2f, id.fontSize * 2f) + tolerance ||
                        !atHead(id, main) || allMains.any { (other, box) ->
                            other != device && inside(Point(box.x, box.y), main, .3f)
                        }) continue
                    fun sameExtent(field: PdfTextBox) = abs(field.y - main.y) <= tolerance &&
                        abs(field.bottom - main.bottom) <= tolerance
                    // Require both the double-width middle cell and the final single cell.
                    // A lone broad hyperlink or an overlapping pin hitbox is insufficient.
                    val paired = rects.any { middle ->
                        sameExtent(middle) && abs(middle.width - main.width * 2f) <= max(.6f, main.width * .12f) &&
                            ((abs(middle.x - main.right) <= tolerance && rects.any { last ->
                                sameExtent(last) && abs(last.width - main.width) <= max(.6f, main.width * .08f) &&
                                    abs(last.x - middle.right) <= tolerance
                            }) || (abs(middle.right - main.x) <= tolerance && rects.any { last ->
                                sameExtent(last) && abs(last.width - main.width) <= max(.6f, main.width * .08f) &&
                                    abs(last.right - middle.x) <= tolerance
                            }))
                    }
                    if (!paired) continue
                    for ((caption, box) in captions) {
                        checkCancelled()
                        if (box.baselineY <= id.bottom + max(.6f, box.fontSize * .4f) ||
                            !inside(Point(box.baselineX, box.baselineY), main, .3f)) continue
                        matches.getOrPut(caption) { linkedSetOf() }.add(device)
                    }
                }
            }
        }
        return matches.mapNotNull { (caption, owners) -> owners.singleOrNull()?.let { caption to it } }.toMap()
    }

    private fun channelNumber(text: String): Int? =
        numberedChannel.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * A numbered signal bank is one property column, not a collection of nearest text centers.
     * Its independent native runs share an insertion anchor and a 1,2,... sequence. Pair that
     * whole bank with the adjacent device property field, including vertical main identifiers.
     */
    private fun channelOwners(
        page: PdfPageText,
        candidates: List<PdfTextBox>,
        devices: List<PdfTextBox>,
        areas: List<PdfTextBox>,
        checkCancelled: () -> Unit,
    ): Map<PdfTextBox, PdfTextBox> {
        if (areas.isEmpty()) return emptyMap()
        val result = linkedMapOf<PdfTextBox, PdfTextBox>()
        val signals = uniquePositions(candidates.filter { channelNumber(it.text) != null }, checkCancelled)
        for ((angle, directional) in signals.groupBy { it.angle }) {
            checkCancelled()
            if (angleDifference(angle, quadrant(angle)) > 3) continue
            val rotation = (360 - quadrant(angle)) % 360
            val normalized = directional.associateWith { corrected(it, page, rotation) }
            val normalizedDevices = devices.associateWith { corrected(it, page, rotation) }
            val rects = installationFields(page, areas).map { corrected(it, page, rotation) }
            data class Match(val device: PdfTextBox, val gap: Float, val offset: Float)
            data class Bank(val signals: List<PdfTextBox>, val matches: List<Match>)
            val banks = mutableListOf<Bank>()
            // Some PDFs omit the middle hit rectangle of a 1:2:1 property block. Its two
            // matching side rectangles still prove the actual device's middle region.
            fun mainFields(device: PdfTextBox, mainId: PdfTextBox): List<PdfTextBox> {
                fun valid(main: PdfTextBox) = inside(Point(mainId.cx, mainId.cy), main, .3f) &&
                    main.height >= mainId.height * 1.4f &&
                    normalizedDevices.none { (other, box) -> other != device && inside(Point(box.cx, box.cy), main, .3f) }
                val native = rects.filter(::valid)
                if (native.isNotEmpty()) return native
                val sides = rects.filter { field ->
                    field.height >= mainId.height * 1.4f && mainId.y >= field.y - .5f &&
                        mainId.y <= field.y + max(mainId.fontSize, field.height * .1f) &&
                        abs(field.cx - mainId.cx) <= mainId.fontSize * 8f
                }
                val recovered = mutableListOf<PdfTextBox>()
                for (left in sides) for (right in sides) {
                    checkCancelled()
                    val gap = right.x - left.right
                    val tolerance = max(.6f, mainId.fontSize * .25f)
                    if (gap <= 0f || abs(left.y - right.y) > tolerance || abs(left.bottom - right.bottom) > tolerance ||
                        abs(left.width - right.width) > max(.6f, left.width * .08f) ||
                        abs(gap - left.width * 2f) > max(.6f, gap * .08f)) continue
                    val main = PdfTextBox("", left.right, left.y, gap, left.height)
                    if (valid(main)) recovered += main
                }
                return recovered.distinct()
            }
            val mainsByDevice = normalizedDevices.mapValues { (device, box) -> mainFields(device, box) }
            val columns = mutableListOf<MutableList<PdfTextBox>>()
            for (signal in directional.sortedBy { normalized.getValue(it).baselineX }) {
                val column = columns.lastOrNull()
                val box = normalized.getValue(signal)
                if (column != null && abs(box.baselineX - normalized.getValue(column.first()).baselineX) <= max(.35f, box.fontSize * .3f)) column += signal
                else columns += mutableListOf(signal)
            }
            for (column in columns) {
                val runs = mutableListOf<MutableList<PdfTextBox>>()
                for (signal in column.sortedBy { normalized.getValue(it).baselineY }) {
                    checkCancelled()
                    val run = runs.lastOrNull()
                    val previous = run?.lastOrNull()
                    val box = normalized.getValue(signal)
                    val before = previous?.let { normalized.getValue(it) }
                    if (run != null && previous != null && before != null &&
                        channelNumber(signal.text) == channelNumber(previous.text)!! + 1 &&
                        box.baselineY - before.baselineY in 0f..(max(box.fontSize, before.fontSize) * 2.2f)) run += signal
                    else runs += mutableListOf(signal)
                }
                for (run in runs) {
                    checkCancelled()
                    if (run.size < 2 || channelNumber(run.first().text) != 1) continue
                    val bank = run.map { normalized.getValue(it) }
                    val matches = mutableListOf<Match>()
                    for ((device, mainId) in normalizedDevices) {
                        checkCancelled()
                        val mains = mainsByDevice.getValue(device).filter { mainId.bottom < bank.first().baselineY }
                        for (main in mains) for (field in rects) {
                            if (!pairedSideField(main, field, mainId.fontSize) ||
                                bank.any { !inside(Point(it.baselineX, it.baselineY), field, .4f) }) continue
                            val endGap = field.bottom - bank.last().baselineY
                            matches += Match(device, abs(endGap), bank.first().baselineX - mainId.baselineX)
                        }
                    }
                    val owners = matches.groupBy { it.device }.map { (_, values) -> values.minBy { it.gap } }.sortedBy { it.gap }
                    val best = owners.firstOrNull() ?: continue
                    banks += Bank(run, owners.filter { it.gap - best.gap < .5f })
                }
            }
            val confirmed = banks.filter { it.matches.size == 1 }.map { it.matches.single() }
            for (bank in banks) {
                checkCancelled()
                val chosen = if (bank.matches.size == 1) bank.matches.single() else {
                    // Repeated adjacent signal banks share a native insertion offset. Calibrate
                    // from unambiguous banks on this same device row to resolve overlapping
                    // mirrored description fields, rather than picking the nearest text center.
                    val fits = bank.matches.mapNotNull { match ->
                        val id = normalizedDevices.getValue(match.device)
                        val offsets = confirmed.filter { anchor ->
                            val other = normalizedDevices.getValue(anchor.device)
                            angleDifference(id.angle, other.angle) <= 3 &&
                                id.fontSize / other.fontSize.coerceAtLeast(.1f) in .85f..1.15f &&
                                abs(id.baselineY - other.baselineY) <= max(id.fontSize, other.fontSize) * 2f
                        }.map { it.offset }.sorted()
                        if (offsets.isEmpty()) null else match to abs(match.offset - offsets[offsets.size / 2])
                    }.sortedBy { it.second }
                    val best = fits.firstOrNull()
                    if (best != null && best.second <= max(.6f, normalizedDevices.getValue(best.first.device).fontSize * .5f) &&
                        (fits.size == 1 || fits[1].second - best.second > .5f)) best.first else null
                }
                if (chosen != null) bank.signals.forEach { result[it] = chosen.device }
            }
        }
        return result
    }

    /** One printed XT identifier can own several separately printed phase labels in its block. */
    private fun extendTerminalPhaseGroups(
        candidates: List<PdfTextBox>,
        devices: List<PdfTextBox>,
        fields: Map<PdfTextBox, PdfTextBox>,
        assigned: MutableMap<PdfTextBox, MutableList<PdfTextBox>>,
        page: PdfPageText,
        checkCancelled: () -> Unit,
    ) {
        val suffix = Regex("[-－](?:L[123]|N1|N24|P24)$", RegexOption.IGNORE_CASE)
        val groups = candidates.filter { suffix.containsMatchIn(it.text.trim()) }.groupBy { box ->
            suffix.replace(box.text.trim(), "") to box.angle
        }
        val taken = assigned.values.flatten().toHashSet()
        for (family in groups.values) {
            checkCancelled()
            val columns = mutableListOf<MutableList<PdfTextBox>>()
            for (box in family.sortedBy { it.baselineX }) {
                val column = columns.lastOrNull()
                if (column != null && abs(box.baselineX - column.first().baselineX) <= max(1f, box.fontSize)) column += box
                else columns += mutableListOf(box)
            }
            for (column in columns) {
                val runs = mutableListOf<MutableList<PdfTextBox>>()
                for (box in column.sortedBy { it.baselineY }) {
                    val run = runs.lastOrNull()
                    if (run != null && box.baselineY - run.last().baselineY <= max(box.height, run.last().height) * 1.3f &&
                        box.baselineY - run.first().baselineY < page.height * .14f) run += box
                    else runs += mutableListOf(box)
                }
                for (run in runs) {
                    checkCancelled()
                    val owners = run.mapNotNull { fields[it] }.distinct()
                    val owner = owners.singleOrNull()?.takeIf { EplanParsing.deviceId(it.text)?.let(EplanParsing::isXt) == true } ?: continue
                    // Multiple actual terminal positions keep their individual functions; only
                    // fill still-unassigned members when this spatial group has one proven owner.
                    if (run.any { it in taken && it !in assigned[owner].orEmpty() }) continue
                    val anchor = run.filter { fields[it] == owner }.minByOrNull { abs(it.baselineY - owner.baselineY) } ?: continue
                    val neighboringPositions = devices.filter { other ->
                        other != owner && EplanParsing.deviceId(other.text)?.let(EplanParsing::isXt) == true &&
                            abs(other.cx - owner.cx) < max(other.width, owner.width).coerceAtLeast(3f)
                    }
                    val familyName = suffix.replace(anchor.text.trim(), "")
                    val runTop = run.minOf { it.baselineY }
                    val runBottom = run.maxOf { it.baselineY }
                    val physicalBoundaries = neighboringPositions.filter { other ->
                        val confirmed = candidates.filter { caption ->
                            fields[caption] == other && suffix.containsMatchIn(caption.text.trim()) &&
                                angleDifference(caption.angle, anchor.angle) <= 3 &&
                                abs(caption.baselineX - anchor.baselineX) <= max(caption.fontSize, anchor.fontSize) * 2f
                        }
                        val separateFamily = confirmed.isNotEmpty() && confirmed.all { suffix.replace(it.text.trim(), "") != familyName }
                        val margin = max(anchor.fontSize, confirmed.maxOfOrNull { it.fontSize } ?: 0f)
                        // A confirmed neighboring phase group supplies a stronger boundary than
                        // its main ID: XT20's label is below all four phases, whereas XT30 has an
                        // ID at each phase. Their main-label midpoint cuts a genuine XT20 L3.
                        val separateCaptionRange = if (other.baselineY < owner.baselineY) {
                            confirmed.all { it.baselineY < runTop - margin }
                        } else confirmed.all { it.baselineY > runBottom + margin }
                        !(separateFamily && separateCaptionRange)
                    }
                    for (box in run) {
                        checkCancelled()
                        // A neighboring actual XT limits this group's installation space even
                        // when its own caption has no hyperlink and failed the distance matcher.
                        // Translate the proven caption/main offset, then use physical midpoints.
                        val projectedY = box.baselineY + owner.baselineY - anchor.baselineY
                        if (physicalBoundaries.any { abs(projectedY - it.baselineY) <= abs(projectedY - owner.baselineY) }) continue
                        if (taken.add(box)) assigned.getOrPut(owner) { mutableListOf() }.add(box)
                    }
                }
            }
        }
    }

    private fun associationScore(device: PdfTextBox, function: PdfTextBox, owner: PdfTextBox?): Float {
        val start = Point(function.baselineX, function.baselineY)
        val end = end(function)
        val diagonal = function.angle % 90 != 0
        val score = if (diagonal) {
            // EPLAN's slanted terminal functions start immediately below/above their own terminal.
            // Weight transverse displacement so long parallel captions cannot slide to a neighbor.
            // Do not use the far endpoint: on B06 p130 it extends toward the NEXT rail's QF
            // row and would steal the caption from its own XT terminal even at perfect PDF precision.
            pointDistance(start, device, 2.5f)
        } else {
            val distance = segmentDistance(start, end, Point(device.cx, device.cy))
            val thickness = max(device.fontSize, function.fontSize) * .5f
            max(0f, distance - thickness)
        }
        if (owner != null && owner.width >= device.width && owner.height >= device.height &&
            (inside(start, owner, function.fontSize) || inside(end, owner, function.fontSize))) return score * .45f
        return score
    }

    private fun mergeLayout(values: List<PdfTextBox>, page: PdfPageText, checkCancelled: () -> Unit): List<AbFunctionEvidence> {
        val remaining = values.toMutableList()
        val result = mutableListOf<PdfTextBox>()
        while (remaining.isNotEmpty()) {
            checkCancelled()
            val first = remaining.minWithOrNull(compareBy<PdfTextBox> { normalCoordinate(it) }.thenBy { alongCoordinate(it) })!!
            remaining.remove(first)
            val group = mutableListOf(first)
            while (true) {
                checkCancelled()
                val previous = group.last()
                val next = remaining.filter { canContinue(previous, it) }.minByOrNull { baselineDistance(previous, it) } ?: break
                group += next
                remaining.remove(next)
            }
            result += EplanParsing.mergeBoxes(group, group.joinToString("") { it.text.trim() })
        }
        val ordered = if (result.all { channelNumber(it.text) != null } && result.map { it.angle }.distinct().size == 1)
            result.sortedWith(compareBy<PdfTextBox> { normalCoordinate(it) }.thenBy { alongCoordinate(it) })
        else EplanParsing.readingOrder(result, page.width, page.height)
        return ordered.map { AbFunctionEvidence(it.text, it, page.page) }
    }

    private fun canContinue(previous: PdfTextBox, next: PdfTextBox): Boolean {
        // Each numbered signal is independently printed even when its baseline is very close.
        if (channelNumber(next.text) != null) return false
        if (angleDifference(previous.angle, next.angle) > 3 || previous.blue != next.blue) return false
        val font = max(previous.fontSize, next.fontSize).coerceAtLeast(1f)
        val across = normalCoordinate(next) - normalCoordinate(previous)
        val along = abs(alongCoordinate(next) - alongCoordinate(previous))
        if (across < font * .55f || across > font * 1.8f || along > font * 1.4f) return false
        if (isContinuation(next.text) || unclosedParenthesis(previous.text)) return true
        // Adjacent terminal ranges and separately prefixed functions remain distinct labels.
        if (Regex("^(?:[0-9]+\\s*[-~～]\\s*[0-9]+|【|\\[[^]]+])").containsMatchIn(next.text.trim())) return false
        return across <= font * 1.3f && along <= font * .5f && previous.text.length >= 10 &&
            previous.fontSize / next.fontSize.coerceAtLeast(.1f) in .85f..1.15f
    }

    private fun isContinuation(text: String): Boolean = Regex("^[（(].+[）)]$").matches(text.trim()) &&
        !Regex("^[（(][0-9]+(?:\\.[0-9]+)?A[）)]$", RegexOption.IGNORE_CASE).matches(text.trim())

    private fun unclosedParenthesis(text: String) = text.count { it == '(' || it == '（' } > text.count { it == ')' || it == '）' }

    private fun mergeCell(boxes: List<PdfTextBox>, page: PdfPageText): List<AbFunctionEvidence> {
        // Device-rail row tolerance is deliberately broad. A cell's closely spaced lines need
        // baseline order instead, otherwise a shorter second line sorts before its own heading.
        val lines = mutableListOf<MutableList<PdfTextBox>>()
        for (box in boxes.sortedBy { it.baselineY }) {
            val line = lines.lastOrNull()
            if (line != null && abs(box.baselineY - line.first().baselineY) <= max(box.fontSize, line.first().fontSize) * .4f) line += box
            else lines += mutableListOf(box)
        }
        val ordered = lines.flatMap { it.sortedBy { box -> box.baselineX } }
        val merged = EplanParsing.mergeBoxes(ordered, ordered.joinToString("") { it.text.trim() })
        return merged.text.split(';', '；').map { it.trim() }.filter(::confirmedFunction)
            .map { AbFunctionEvidence(it, merged.copy(text = it), page.page) }
    }

    private fun bounds(boxes: List<PdfTextBox>): PdfTextBox = EplanParsing.mergeBoxes(boxes, "")
    private fun quadrant(angle: Int) = ((angle + 45) / 90 * 90) % 360
    private fun angleDifference(a: Int, b: Int): Int = min(abs(a - b) % 360, 360 - abs(a - b) % 360)
    private fun inside(point: Point, box: PdfTextBox, margin: Float = 0f) = point.x >= box.x - margin && point.x <= box.right + margin && point.y >= box.y - margin && point.y <= box.bottom + margin
    private fun pointDistance(point: Point, box: PdfTextBox, horizontalWeight: Float = 1f): Float {
        val dx = max(0f, max(box.x - point.x, point.x - box.right)) * horizontalWeight
        val dy = max(0f, max(box.y - point.y, point.y - box.bottom))
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun rectangleDistance(a: PdfTextBox, b: PdfTextBox): Float = hypot(
        max(0f, max(a.x - b.right, b.x - a.right)).toDouble(),
        max(0f, max(a.y - b.bottom, b.y - a.bottom)).toDouble(),
    ).toFloat()

    private fun end(box: PdfTextBox): Point {
        val angle = Math.toRadians(box.angle.toDouble())
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        // Joined runs and cached boxes can have advance=width (not true diagonal advance).
        // The projection after removing glyph thickness recovers the baseline length.
        val length = max(0f, abs(dx) * box.width + abs(dy) * box.height - 2f * abs(dx * dy) * box.fontSize)
        return Point(box.baselineX + dx * length, box.baselineY + dy * length)
    }

    private fun segmentDistance(start: Point, end: Point, point: Point): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val square = dx * dx + dy * dy
        val parameter = if (square <= .001f) 0f else ((point.x - start.x) * dx + (point.y - start.y) * dy) / square
        val t = parameter.coerceIn(0f, 1f)
        return hypot((point.x - start.x - t * dx).toDouble(), (point.y - start.y - t * dy).toDouble()).toFloat()
    }

    private fun alongCoordinate(box: PdfTextBox): Float {
        val angle = Math.toRadians(box.angle.toDouble())
        return box.baselineX * cos(angle).toFloat() + box.baselineY * sin(angle).toFloat()
    }

    private fun normalCoordinate(box: PdfTextBox): Float {
        val angle = Math.toRadians(box.angle.toDouble())
        return -box.baselineX * sin(angle).toFloat() + box.baselineY * cos(angle).toFloat()
    }

    private fun baselineDistance(a: PdfTextBox, b: PdfTextBox): Float = hypot((a.baselineX - b.baselineX).toDouble(), (a.baselineY - b.baselineY).toDouble()).toFloat()
}
