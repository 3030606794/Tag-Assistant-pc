package cn.guangyao.eplan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A/B-only installation order. Pixels provide geometry, never text or a new device. */
object AbInstallationOrder {
    /** Complete displayed PDF page, packed ARGB pixels. PDF /Rotate is already applied. */
    data class Raster(val width: Int, val height: Int, val pixels: IntArray)
    internal data class OrderResult(val positions: List<AbInstalledPosition>, val confident: Boolean)

    private const val RED = 1
    private const val CYAN = 2
    private const val PURPLE = 3
    private const val RAIL = 4
    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int, val count: Int = 0) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }
    private data class Grid(val width: Int, val height: Int, val colors: ByteArray, val dark: ByteArray)
    private data class Band(val top: Int, val bottom: Int) {
        val height get() = bottom - top + 1
        val center get() = (top + bottom) / 2f
        fun distance(y: Float) = max(0f, max(top - y, y - bottom))
    }
    private data class Located(
        val original: AbInstalledPosition,
        val x: Float,
        val y: Float,
        val font: Float,
        val labelBottom: Float,
        val aboveBody: Boolean,
        val labelLeft: Float,
        val labelRight: Float,
        val horizontalLabel: Boolean,
    )
    private data class SideBody(val top: Int, val probeX: Float)

    /**
     * Recognize a colored cabinet's duct frame and installation bands. When that evidence is
     * absent/ambiguous, retain the existing region/direction and use only corroborated common
     * text edges to repair a row split caused by unequal vertical identifier lengths.
     * No cabinet names, device IDs, drawing filename or expected row count enter the analysis.
     */
    internal fun order(
        page: PdfPageText,
        devices: List<PdfTextBox>,
        raster: Raster?,
        rotationOverride: Int? = null,
        checkCancelled: () -> Unit = {},
    ): OrderResult {
        val previous = AbLayoutAnalysis.installedPositions(page, devices, rotationOverride, checkCancelled)
        fun fallback(): OrderResult {
            val displayRotation = ((page.rotation % 360) + 360) % 360
            if (displayRotation == 0) return OrderResult(alignedTextFallback(page, previous, checkCancelled), false)
            // A PDF viewer's /Rotate does not define the cabinet's installation direction.
            // When artwork cannot prove an upright orientation, infer/order in the source
            // drawing coordinates and map back, just as native A/B ownership already does.
            val undo = (360 - displayRotation) % 360
            fun unrotate(box: PdfTextBox): PdfTextBox {
                val first = EplanParsing.rotate(box.x, box.y, page.width, page.height, undo)
                val last = EplanParsing.rotate(box.right, box.bottom, page.width, page.height, undo)
                val baseline = EplanParsing.rotate(box.baselineX, box.baselineY, page.width, page.height, undo)
                return box.copy(x = min(first.first, last.first), y = min(first.second, last.second),
                    width = abs(first.first - last.first), height = abs(first.second - last.second),
                    baselineX = baseline.first, baselineY = baseline.second, angle = (box.angle + undo) % 360)
            }
            val transformed = (page.boxes + devices).distinct().associateWith {
                checkCancelled()
                unrotate(it)
            }
            val originals = transformed.entries.associate { it.value to it.key }
            val source = page.copy(
                width = if (displayRotation % 180 == 0) page.width else page.height,
                height = if (displayRotation % 180 == 0) page.height else page.width,
                boxes = page.boxes.map { transformed.getValue(it) }, layoutBoxes = null, rotation = 0,
            )
            val sourceOverride = rotationOverride?.let { (it + displayRotation) % 360 }
            val sourcePositions = AbLayoutAnalysis.installedPositions(source, devices.map { transformed.getValue(it) },
                sourceOverride, checkCancelled)
            val ordered = alignedTextFallback(source, sourcePositions, checkCancelled).map {
                it.copy(box = originals.getValue(it.box), rotation = (it.rotation + undo) % 360)
            }
            return OrderResult(ordered, false)
        }
        if (previous.size < 4 || raster == null || page.width <= 0 || page.height <= 0) return fallback()
        if (raster.width < 32 || raster.height < 32 || raster.width.toLong() * raster.height != raster.pixels.size.toLong()) return fallback()
        val aspect = (raster.width.toFloat() / raster.height) / (page.width / page.height)
        if (aspect !in .97f..1.03f) return fallback()
        val grid = classify(raster, checkCancelled)
        val mask = ByteArray(grid.colors.size)
        // Close the thin black outlines/dots in duct artwork only for connected-frame detection.
        // Raw color coverage is retained for the installation-band measurements below.
        for (y in 0 until grid.height) {
            checkCancelled()
            for (x in 0 until grid.width) if (grid.colors[y * grid.width + x].toInt() in RED..PURPLE) {
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until grid.width && ny in 0 until grid.height) mask[ny * grid.width + nx] = 1
                }
            }
        }
        val frames = components(mask, grid.width, grid.height, checkCancelled)
            .filter { it.count > grid.colors.size * .025f && it.width > grid.width * .2f && it.height > grid.height * .2f }
            .sortedByDescending { it.count }
        val frame = frames.firstOrNull() ?: return fallback()
        // Separate comparable cabinets on one page need independent plans; do not combine them
        // across an empty gutter using a page-wide projection.
        if (frames.getOrNull(1)?.let { it.count > frame.count * .25f } == true) return fallback()
        val orange = ByteArray(grid.colors.size) { if (grid.colors[it].toInt() == RAIL) 1 else 0 }
        val rails = components(orange, grid.width, grid.height, checkCancelled)
            .filter { it.count >= 4 && it.left >= frame.left && it.right <= frame.right && it.top >= frame.top && it.bottom <= frame.bottom }
        val conventional = orientation(grid, frame, rails, checkCancelled)
        val detected = conventional
            ?: corroboratedOrientation(page, devices, grid, frame, rails, checkCancelled)
        val rotation = rotationOverride?.takeIf { it in setOf(0, 90, 180, 270) } ?: detected ?: return fallback()
        val upright = turn(grid, rotation, checkCancelled)
        val bounds = turn(frame, grid.width, grid.height, rotation)
        val located = previous.map {
            checkCancelled()
            val xy = EplanParsing.rotate(it.box.cx / page.width * grid.width, it.box.cy / page.height * grid.height,
                grid.width.toFloat(), grid.height.toFloat(), rotation)
            val first = EplanParsing.rotate(it.box.x / page.width * grid.width, it.box.y / page.height * grid.height,
                grid.width.toFloat(), grid.height.toFloat(), rotation)
            val last = EplanParsing.rotate(it.box.right / page.width * grid.width, it.box.bottom / page.height * grid.height,
                grid.width.toFloat(), grid.height.toFloat(), rotation)
            val font = it.box.fontSize * grid.width / page.width
            val angle = ((it.box.angle + rotation) % 360 + 360) % 360
            Located(it, xy.first, xy.second, font, max(first.second, last.second),
                angle in 255..285 && abs(last.second - first.second) >= font * 1.5f,
                min(first.first, last.first), max(first.first, last.first),
                angle in 0..15 || angle in 165..195 || angle in 345..359)
        }
        val margin = max(2f, located.map { it.font }.sorted()[located.size / 2] * 2f)
        if (located.count { it.x >= bounds.left - margin && it.x <= bounds.right + margin &&
                it.y >= bounds.top - margin && it.y <= bounds.bottom + margin } < located.size * .95f) return fallback()
        val coreBands = installationBands(upright, bounds, checkCancelled)
        val rawBands = edgeInstallationBands(upright, bounds, located, coreBands, checkCancelled)
        if (rawBands.size < 2) return fallback()
        // Bottom duct openings and blank plate compartments are not extra installation rows.
        val bands = rawBands.filter { band -> located.any { band.distance(it.y) <= max(it.font * 3f, band.height * .1f) } }
        if (bands.size < 2) return fallback()
        val medianHeight = bands.map { it.height }.sorted()[bands.size / 2]
        val byBand = Array(bands.size) { mutableListOf<Located>() }
        for (item in located) {
            checkCancelled()
            var index = bands.indices.minWithOrNull(compareBy<Int> { bands[it].distance(item.y) }
                .thenBy { abs(bands[it].center - item.y) })!!
            val local = localCompartment(upright, bounds, item.x, item.y, checkCancelled)
            if (local != null && local.height <= medianHeight * 1.9f) {
                // A short side compartment may extend below the main rail (e.g. copper busbar
                // with its identifier below the body). Its overlap assigns the whole compartment.
                // A tall circuit breaker spanning several bands instead keeps its start band;
                // other devices lower in that compartment retain their own installation band.
                val best = bands.indices.maxByOrNull { overlap(local, bands[it]) }!!
                if (overlap(local, bands[best]) >= min(local.height, bands[best].height) * .5f) index = best
            } else if (local != null && item.aboveBody) {
                // A tall component can occupy a side pocket without the neighbouring horizontal
                // duct. Its identifier may still sit in the preceding main band. Look immediately
                // below that label for the neutral, dark body edge; the next clear installation
                // band owns a body beginning in a duct gap. A large breaker whose body begins
                // inside a band remains in that starting band, regardless of its overall height.
                val start = bodyStart(upright, bounds, item, checkCancelled)
                if (start != null) bands.indices.firstOrNull { bands[it].bottom >= start }?.let { index = it }
            }
            if (rawBands.size > coreBands.size && item.original.box.sourceRun >= 0) {
                // A newly recovered outer band must not swallow a lower row's raised label
                // printed over the separating duct. Check its nearby real body, including
                // both vertical text directions, only at a newly introduced band boundary.
                val angle = (item.original.box.angle + rotation) % 360
                if (angle in 75..105 || angle in 255..285) {
                    val start = bodyStart(upright, bounds, item, checkCancelled)
                    val bodyBand = start?.let { y -> bands.indices.firstOrNull { bands[it].bottom >= y } }
                    if (bodyBand != null && bodyBand != index &&
                        (bands[index] !in coreBands || bands[bodyBand] !in coreBands)) index = bodyBand
                }
            }
            byBand[index] += item
        }
        val ordered = byBand.flatMapIndexed { index, items ->
            // On the supplementary native/rail plan, a side pocket can extend through a
            // main horizontal duct. Its lower device
            // belongs after the upper main row when its actual body starts below that row,
            // even if its label was assigned to the nearest upper band.
            // Existing three-color plans retain their side-pocket order: isolated black
            // edges are insufficient to move just part of a differently sized drive row.
            val lowerBodies = items.mapNotNull { item ->
                if (conventional != null || detected == null || rotation != detected ||
                    item.original.box.sourceRun < 0) null else {
                    val start = bodyStart(upright, bounds, item, checkCancelled)
                    if (start != null && start > bands[index].bottom) item to start else null
                }
            }
            val below = lowerBodies.map { it.first }.toSet()
            val lowerRows = mutableListOf<MutableList<Pair<Located, Int>>>()
            for (item in lowerBodies.sortedBy { it.second }) {
                val row = lowerRows.lastOrNull()
                if (row != null && item.second - row.first().second <= max(2f, item.first.font * 3f)) row += item
                else lowerRows += mutableListOf(item)
            }
            splitInstalledRows(upright, bounds, items.filter { it !in below }, checkCancelled) +
                lowerRows.map { row -> row.map { it.first } }
        }.flatMap { items ->
            checkCancelled()
            // Stable left-to-right order inside the same physical installation band. Near-equal
            // x coordinates are a vertical sub-stack, so read its upper element first.
            val columns = mutableListOf<MutableList<Located>>()
            for (item in items.sortedBy { it.x }) {
                val column = columns.lastOrNull()
                val tolerance = max(.75f, item.font * .3f)
                if (column != null && abs(item.x - column.first().x) <= tolerance) column += item
                else columns += mutableListOf(item)
            }
            columns.flatMap { column -> column.sortedBy { it.y }.map { it.original.copy(rotation = rotation) } }
        }
        return OrderResult(ordered, true)
    }

    /** A short top duct may not reach the global 55% projection. The connected frame still
     * bounds an outer compartment, but retain it only with native devices and real body edges.
     * Do not extend ordinary bands a few pixels into decorative frame outlines. */
    private fun edgeInstallationBands(
        grid: Grid, frame: Rect, located: List<Located>, bands: List<Band>, checkCancelled: () -> Unit,
    ): List<Band> {
        if (bands.isEmpty()) return bands
        val medianHeight = bands.map { it.height }.sorted()[bands.size / 2]
        val first = bands.first(); val last = bands.last()
        fun valid(candidate: Band): Boolean {
            if (candidate.height < medianHeight * .65f) return false
            val members = located.filter { it.y >= candidate.top && it.y <= candidate.bottom &&
                it.original.box.sourceRun >= 0 }
            if (members.size < 3 || members.maxOf { it.x } - members.minOf { it.x } < frame.width * .2f) return false
            val below = members.filter { bodyStart(grid, frame, it, checkCancelled) != null }.toSet()
            if (below.size >= 3) return true
            if (below.isEmpty()) return false
            // Horizontal IDs can sit beside grounding bars or gateways, and instrument IDs
            // may be printed inside their body. A shared closed local compartment supplies
            // the missing row boundary, but still require an independent adjacent body edge.
            val tolerance = max(2f, frame.height * .004f)
            val sides = members.associateWith { sideBody(grid, frame, it, checkCancelled) }
            val pockets = members.mapNotNull { item ->
                // A side label itself may overlap a vertical duct. Its nearby proved body
                // supplies an interior probe; the text's center must not veto that pocket.
                val local = localCompartment(grid, frame, item.x, item.y, checkCancelled)
                    ?: sides[item]?.let { side -> localCompartment(grid, frame, side.probeX, item.y, checkCancelled) }
                    ?: return@mapNotNull null
                if (local.height < candidate.height * .5f || overlap(local, candidate) < local.height * .95f) null
                else item to local
            }
            return pockets.any { (_, anchor) ->
                val common = pockets.filter { (_, local) -> abs(local.top - anchor.top) <= tolerance &&
                    abs(local.bottom - anchor.bottom) <= tolerance }.map { it.first }
                common.size >= 3 && common.maxOf { it.x } - common.minOf { it.x } >= frame.width * .2f &&
                    common.any { it in below } && common.any { it !in below && sides[it] != null }
            }
        }
        val before = Band(frame.top, first.top - 1)
        val after = Band(last.bottom + 1, frame.bottom)
        return buildList {
            if (valid(before)) add(before)
            addAll(bands)
            if (valid(after)) add(after)
        }
    }

    /** A broad duct compartment can contain several installed rows. Split only when native
     * IDs align over several actual component bodies at two well separated heights. This is
     * local to an already established cabinet band: no page turn comes from text alignment.
     * Using the body-facing edge handles mixed ID lengths and small fuse lettering together. */
    private fun splitInstalledRows(
        grid: Grid, frame: Rect, items: List<Located>, checkCancelled: () -> Unit,
    ): List<List<Located>> {
        if (items.size < 6) return listOf(items)
        val font = items.map { it.font }.sorted()[items.size / 2]
        val tolerance = max(2f, font * 3f)
        val candidates = mutableListOf<MutableList<Located>>()
        for (item in items.sortedBy { it.labelBottom }) {
            checkCancelled()
            val box = item.original.box
            if (box.sourceRun < 0 || box.sourceObject < 0) continue
            val group = candidates.lastOrNull()
            if (group != null && item.labelBottom - group.first().labelBottom <= tolerance) group += item
            else candidates += mutableListOf(item)
        }
        val strong = candidates.filter { group ->
            group.size >= 3 && group.maxOf { it.x } - group.minOf { it.x } >= frame.width * .18f &&
                group.count { bodyStart(grid, frame, it, checkCancelled) != null } >= max(2, (group.size + 1) / 2)
        }
        fun edge(group: List<Located>) = group.map { it.labelBottom }.sorted()[group.size / 2]
        fun sharedWidth(first: List<Located>, second: List<Located>) =
            min(first.maxOf { it.x }, second.maxOf { it.x }) - max(first.minOf { it.x }, second.minOf { it.x })
        // Compact accessories mounted just above breakers are part of that physical layer.
        // Merge those corroborated, overlapping near layers before seeking a separate row;
        // their small offset must not veto a clearly distant contactor row below them.
        val rows = mutableListOf<MutableList<Located>>()
        for (candidate in strong) {
            val previous = rows.lastOrNull()
            if (previous != null && edge(candidate) - edge(previous) < font * 8f &&
                sharedWidth(previous, candidate) >= frame.width * .12f) previous += candidate
            else rows += candidate.toMutableList()
        }
        if (rows.size < 2) return listOf(items)
        if (rows.zipWithNext().any { (before, after) -> edge(after) - edge(before) < font * 8f }) return listOf(items)
        if (rows.zipWithNext().any { (before, after) ->
                sharedWidth(before, after) < frame.width * .12f
            }) return listOf(items)
        val grouped = rows.map { it.toMutableList() }.toMutableList()
        val assigned = rows.flatten().toHashSet()
        // Side components retain their own height when clearly between major rows instead of
        // being interleaved into a densely populated DIN row merely because their x is smaller.
        for (item in items.filter { it !in assigned }.sortedBy { it.labelBottom }) {
            checkCancelled()
            val side = sideBody(grid, frame, item, checkCancelled)
            if (side != null) {
                val bodyRow = rows.mapNotNull { row ->
                    val starts = row.mapNotNull { bodyStart(grid, frame, it, checkCancelled) }.sorted()
                    if (starts.isEmpty()) null else row to starts[starts.size / 2]
                }.minByOrNull { abs(it.second - side.top) }
                if (bodyRow != null && abs(bodyRow.second - side.top) <= max(2f, font * 3f)) {
                    // A grounding-bar label beside its body can be lower than the short
                    // fuse IDs above adjacent bodies on the very same physical rail.
                    grouped[rows.indexOf(bodyRow.first)] += item
                    continue
                }
            }
            val nearest = grouped.minByOrNull { abs(edge(it) - item.labelBottom) }!!
            if (abs(edge(nearest) - item.labelBottom) <= tolerance) nearest += item
            else grouped += mutableListOf(item)
        }
        return grouped.sortedBy(::edge)
    }

    private fun classify(raster: Raster, checkCancelled: () -> Unit): Grid {
        val scale = min(1f, 1000f / max(raster.width, raster.height))
        val width = max(1, (raster.width * scale).roundToInt())
        val height = max(1, (raster.height * scale).roundToInt())
        val colors = ByteArray(width * height)
        val dark = ByteArray(width * height)
        for (y in 0 until height) {
            checkCancelled()
            val sourceY = min(raster.height - 1, ((y + .5f) / height * raster.height).toInt())
            for (x in 0 until width) {
                val sourceX = min(raster.width - 1, ((x + .5f) / width * raster.width).toInt())
                val argb = raster.pixels[sourceY * raster.width + sourceX]
                val r = argb ushr 16 and 255; val g = argb ushr 8 and 255; val b = argb and 255
                val low = min(r, min(g, b)); val high = max(r, max(g, b))
                // Blue native text and colored ducts are not neutral component outlines.
                if (high < 115 && high - low < 45) dark[y * width + x] = 1
                val muted = low >= 55 && high <= 235 && high - low in 15..105
                colors[y * width + x] = when {
                    r >= 150 && g in 70..210 && b <= 85 && r - g >= 40 && g - b >= 40 -> RAIL
                    muted && r - g >= 12 && r - b >= 8 && abs(g - b) <= 22 -> RED
                    muted && g - r >= 8 && b - r >= 8 && abs(g - b) <= 22 -> CYAN
                    muted && b - r >= 10 && b - g >= 20 && r >= g - 15 -> PURPLE
                    else -> 0
                }.toByte()
            }
        }
        return Grid(width, height, colors, dark)
    }

    private fun components(mask: ByteArray, width: Int, height: Int, checkCancelled: () -> Unit): List<Rect> {
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = mutableListOf<Rect>()
        for (start in mask.indices) {
            if (start % 4096 == 0) checkCancelled()
            if (mask[start].toInt() == 0 || seen[start]) continue
            var read = 0; var end = 1
            queue[0] = start; seen[start] = true
            var left = start % width; var right = left; var top = start / width; var bottom = top
            while (read < end) {
                if (read % 4096 == 0) checkCancelled()
                val index = queue[read++]; val x = index % width; val y = index / width
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (!seen[next] && mask[next].toInt() != 0) { seen[next] = true; queue[end++] = next }
                }
            }
            if (end >= 4) result += Rect(left, top, right, bottom, end)
        }
        return result
    }

    private fun orientation(grid: Grid, frame: Rect, rails: List<Rect>, checkCancelled: () -> Unit): Int? {
        val scores = listOf(0, 90, 180, 270).mapNotNull { angle ->
            checkCancelled()
            val bounds = turn(frame, grid.width, grid.height, angle)
            val transformedRails = rails.map { turn(it, grid.width, grid.height, angle) }
            val horizontal = transformedRails.filter { it.width >= it.height * 3 && it.width >= bounds.width * .035f }.sumOf { it.count }
            val vertical = transformedRails.filter { it.height >= it.width * 3 && it.height >= bounds.height * .035f }.sumOf { it.count }
            val clearRails = horizontal >= max(8, frame.count / 1000) && horizontal >= vertical * 3
            var red = 0; var cyan = 0; var purple = 0
            var topRed = 0; var bottomCyan = 0; var rightPurple = 0; var leftPurple = 0
            for (y in frame.top..frame.bottom) {
                checkCancelled()
                for (x in frame.left..frame.right) {
                    val color = grid.colors[y * grid.width + x].toInt()
                    if (color !in RED..PURPLE) continue
                    val point = point(x, y, grid.width, grid.height, angle)
                    val u = (point.first - bounds.left).toFloat() / bounds.width
                    val v = (point.second - bounds.top).toFloat() / bounds.height
                    when (color) {
                        RED -> { red++; if (v < .2f) topRed++ }
                        CYAN -> { cyan++; if (v > .8f) bottomCyan++ }
                        PURPLE -> { purple++; if (u > .8f) rightPurple++; if (u < .2f) leftPurple++ }
                    }
                }
            }
            if (min(red, min(cyan, purple)) < frame.width * frame.height * .005f || rightPurple < purple * .65f) return@mapNotNull null
            // Fully populated rails often show only tiny orange ends. A connected colored frame
            // with several full-width duct separators is independent structural evidence; visible
            // orange rails strengthen the orientation but are not a prerequisite for that case.
            if (!clearRails && installationBands(turn(grid, angle, checkCancelled), bounds, checkCancelled).size < 2) {
                return@mapNotNull null
            }
            angle to (4f * (rightPurple - leftPurple) / purple + topRed.toFloat() / red +
                bottomCyan.toFloat() / cyan + if (clearRails) .25f else 0f)
        }.sortedByDescending { it.second }
        val best = scores.firstOrNull() ?: return null
        if (scores.getOrNull(1)?.let { best.second - it.second < 1f } == true) return null
        return best.first
    }

    /**
     * Some cabinets omit one or more duct colors, so the colored convention is unavailable.
     * Require independent physical rail/band evidence, actual lengths printed on the ducts,
     * and native ID/model/function triplets placing properties below their installed IDs.
     * A majority of device text angles alone can never rotate a cabinet.
     */
    private fun corroboratedOrientation(
        page: PdfPageText, devices: List<PdfTextBox>, grid: Grid, frame: Rect,
        rails: List<Rect>, checkCancelled: () -> Unit,
    ): Int? {
        fun pixel(x: Float, y: Float) = x / page.width * grid.width to y / page.height * grid.height
        fun inside(x: Float, y: Float) = x >= frame.left && x <= frame.right && y >= frame.top && y <= frame.bottom
        fun difference(first: Int, second: Int): Int {
            val delta = abs(first - second) % 360
            return min(delta, 360 - delta)
        }
        // An exact length with a unit, over actual duct artwork, excludes type numbers,
        // title-block sizes and strings such as WPD_104_1x25+1x16 printed on components.
        val length = Regex("^[0-9]+(?:[.][0-9]+)?\\s*(?:mm|毫米)$", RegexOption.IGNORE_CASE)
        val dimensions = page.boxes.filter { box ->
            checkCancelled()
            if (!box.blue || !length.matches(box.text.trim()) ||
                listOf(0, 90, 180, 270).none { difference(box.angle, it) <= 3 }) return@filter false
            val center = pixel(box.cx, box.cy)
            if (!inside(center.first, center.second)) return@filter false
            val radius = (box.fontSize * grid.width / page.width * .3f).roundToInt().coerceIn(1, 3)
            val x = center.first.roundToInt(); val y = center.second.roundToInt()
            var total = 0; var covered = 0
            for (row in max(frame.top, y - radius)..min(frame.bottom, y + radius)) {
                for (column in max(frame.left, x - radius)..min(frame.right, x + radius)) {
                    total++
                    if (grid.colors[row * grid.width + column].toInt() in RED..PURPLE) covered++
                }
            }
            covered >= total * .7f
        }.distinctBy { Triple((it.cx * 2).roundToInt(), (it.cy * 2).roundToInt(), it.angle) }
        if (dimensions.size < 3) return null
        fun native(box: PdfTextBox) = box.sourceObject >= 0 && box.sourceRun >= 0 &&
            box.sourceSequence >= 0 && box.sourceEndSequence >= box.sourceSequence
        val ordered = page.boxes.filter(::native).sortedBy { it.sourceSequence }
        val deviceSet = devices.toHashSet()
        val properties = devices.mapNotNull { device ->
            checkCancelled()
            if (!native(device)) return@mapNotNull null
            val next = ordered.asSequence().filter { it.sourceSequence > device.sourceEndSequence }.take(2).toList()
            if (next.size != 2 || next.any { it in deviceSet } || next[0].sourceRun <= device.sourceRun ||
                next[1].sourceRun <= next[0].sourceRun || next[0].sourceEndSequence >= next[1].sourceSequence) return@mapNotNull null
            val caption = next.singleOrNull { it.blue } ?: return@mapNotNull null
            val model = next.singleOrNull { !it.blue } ?: return@mapNotNull null
            if (!AbLayoutAnalysis.confirmedFunction(caption.text) ||
                EplanParsing.deviceId(caption.text, requireLeader = false) != null ||
                !Regex("[A-Za-z][A-Za-z0-9_./-]*[0-9]").containsMatchIn(model.text) ||
                difference(caption.angle, model.angle) > 3 ||
                caption.fontSize / model.fontSize.coerceAtLeast(.1f) !in .8f..1.25f) return@mapNotNull null
            val head = pixel(device.baselineX, device.baselineY)
            val foot = pixel(caption.baselineX, caption.baselineY)
            val type = pixel(model.baselineX, model.baselineY)
            if (!inside(head.first, head.second) || !inside(foot.first, foot.second) ||
                !inside(type.first, type.second)) return@mapNotNull null
            device to caption
        }
        if (properties.size < 3) return null
        val candidates = listOf(0, 90, 180, 270).filter { rotation ->
            checkCancelled()
            val bounds = turn(frame, grid.width, grid.height, rotation)
            val transformedRails = rails.map { turn(it, grid.width, grid.height, rotation) }
            // Populated rails may expose only short ends. Merge their parallel upper/lower
            // edges before counting independent rails; two edges of one rail are one vote.
            val horizontalPieces = transformedRails.filter { it.width >= it.height * 3 && it.width >= bounds.width * .015f }
            val horizontal = horizontalPieces.sumOf { it.count }
            val vertical = transformedRails.filter { it.height >= it.width * 3 && it.height >= bounds.height * .015f }.sumOf { it.count }
            val railRows = mutableListOf<Float>()
            for (rail in horizontalPieces.sortedBy { it.top }) {
                val center = (rail.top + rail.bottom) / 2f
                if (railRows.isEmpty() || center - railRows.last() > max(4f, bounds.height * .025f)) railRows += center
            }
            // Quarter-turn sampling may lose a one-pixel rail edge. A third independent
            // rail row compensates for that area loss; the length/property votes below
            // still have to corroborate the same direction across the cabinet.
            val minimumRailArea = max(8, frame.count / (if (railRows.size >= 3) 1500 else 1000))
            if (horizontal < minimumRailArea || horizontal < vertical * 3 ||
                railRows.size < 2 || railRows.last() - railRows.first() < bounds.height * .15f ||
                installationBands(turn(grid, rotation, checkCancelled), bounds, checkCancelled).size < 2) return@filter false
            fun rotated(x: Float, y: Float): Pair<Float, Float> {
                val p = pixel(x, y)
                return EplanParsing.rotate(p.first, p.second, grid.width.toFloat(), grid.height.toFloat(), rotation)
            }
            val lengths = dimensions.filter { difference((it.angle + rotation) % 360, 0) <= 3 }
            if (lengths.size < 3 || lengths.size < dimensions.size * .8f) return@filter false
            val dimensionRows = lengths.map { rotated(it.cx, it.cy).second }
            if (dimensionRows.maxOrNull()!! - dimensionRows.minOrNull()!! < bounds.height * .25f) return@filter false
            val votes = properties.mapNotNull { (device, caption) ->
                val head = rotated(device.baselineX, device.baselineY)
                val foot = rotated(caption.baselineX, caption.baselineY)
                val dy = foot.second - head.second
                val dx = foot.first - head.first
                val font = device.fontSize * grid.width / page.width
                if (abs(dy) < max(font * 2f, abs(dx) * 1.5f)) null else head.second to (dy > 0f)
            }
            val supporting = votes.filter { it.second }
            supporting.size >= 3 && supporting.size >= votes.size * .8f &&
                supporting.maxOf { it.first } - supporting.minOf { it.first } >= bounds.height * .2f
        }
        return candidates.singleOrNull()
    }

    private fun installationBands(grid: Grid, frame: Rect, checkCancelled: () -> Unit): List<Band> {
        val rows = BooleanArray(frame.height)
        for (y in frame.top..frame.bottom) {
            checkCancelled()
            var count = 0
            for (x in frame.left..frame.right) if (grid.colors[y * grid.width + x].toInt() in RED..PURPLE) count++
            rows[y - frame.top] = count >= frame.width * .55f
        }
        val closeGap = max(1, (frame.height * .002f).roundToInt())
        var index = 0
        while (index < rows.size) {
            if (rows[index]) { index++; continue }
            val start = index
            while (index < rows.size && !rows[index]) index++
            if (start > 0 && index < rows.size && index - start <= closeGap) for (i in start until index) rows[i] = true
        }
        val ducts = mutableListOf<Band>()
        index = 0
        while (index < rows.size) {
            if (!rows[index]) { index++; continue }
            val start = index
            while (index < rows.size && rows[index]) index++
            if (index - start >= max(2f, frame.height * .008f)) ducts += Band(start + frame.top, index - 1 + frame.top)
        }
        return ducts.zipWithNext().mapNotNull { (before, after) ->
            val band = Band(before.bottom + 1, after.top - 1)
            band.takeIf { it.height >= max(4f, frame.height * .035f) }
        }
    }

    private fun localCompartment(grid: Grid, frame: Rect, x: Float, y: Float, checkCancelled: () -> Unit): Band? {
        val centerX = x.roundToInt().coerceIn(frame.left, frame.right)
        val centerY = y.roundToInt().coerceIn(frame.top, frame.bottom)
        val halfWidth = max(1, (frame.width * .009f).roundToInt())
        val left = max(frame.left, centerX - halfWidth); val right = min(frame.right, centerX + halfWidth)
        fun blocked(row: Int): Boolean {
            var count = 0
            for (column in left..right) if (grid.colors[row * grid.width + column].toInt() in RED..PURPLE) count++
            return count > (right - left + 1) * .45f
        }
        if (blocked(centerY)) return null
        var top = centerY; var bottom = centerY
        while (top > frame.top && !blocked(top - 1)) { checkCancelled(); top-- }
        while (bottom < frame.bottom && !blocked(bottom + 1)) { checkCancelled(); bottom++ }
        return Band(top, bottom)
    }

    private fun bodyStart(grid: Grid, frame: Rect, item: Located, checkCancelled: () -> Unit): Int? {
        val halfWidth = max(2f, item.font * 1.5f)
        val left = (item.x - halfWidth).roundToInt().coerceIn(frame.left, frame.right)
        val right = (item.x + halfWidth).roundToInt().coerceIn(frame.left, frame.right)
        val first = item.labelBottom.roundToInt().coerceIn(frame.top, frame.bottom)
        val last = min(frame.bottom - 3, (item.labelBottom + item.font * 4f).roundToInt())
        fun coverage(y: Int): Float {
            var count = 0
            for (x in left..right) if (grid.dark[y * grid.width + x].toInt() != 0) count++
            return count.toFloat() / (right - left + 1)
        }
        for (y in first..last) {
            checkCancelled()
            // A body edge is broad, with more neutral artwork below it. Ignore isolated dots,
            // colored legends, and distant equipment in another installation band.
            if (coverage(y) >= .6f && (coverage(y + 1) + coverage(y + 2) + coverage(y + 3)) / 3f >= .12f) return y
        }
        return null
    }

    /** Only supports an already bounded outer compartment; this cannot create a cabinet
     * direction or installation row on its own. Look beside horizontal native identifiers. */
    private fun sideBody(grid: Grid, frame: Rect, item: Located, checkCancelled: () -> Unit): SideBody? {
        if (!item.horizontalLabel) return null
        val halfHeight = max(2f, item.font * .75f)
        val top = (item.y - halfHeight).roundToInt().coerceIn(frame.top, frame.bottom)
        val bottom = (item.y + halfHeight).roundToInt().coerceIn(frame.top, frame.bottom)
        fun coverage(x: Int): Float {
            if (x !in frame.left..frame.right) return 0f
            var count = 0
            for (y in top..bottom) if (grid.dark[y * grid.width + x].toInt() != 0) count++
            return count.toFloat() / (bottom - top + 1)
        }
        for (direction in listOf(-1, 1)) {
            val edge = if (direction < 0) item.labelLeft else item.labelRight
            for (distance in 1..max(2, (item.font * 4f).roundToInt())) {
                checkCancelled()
                val x = edge.roundToInt() + distance * direction
                if (coverage(x) >= .6f && (coverage(x + direction) + coverage(x + direction * 2) +
                        coverage(x + direction * 3)) / 3f >= .12f) {
                    val start = (top..bottom).filter { grid.dark[it * grid.width + x].toInt() != 0 }
                        .minByOrNull { abs(it - item.y) } ?: continue
                    var upper = start
                    val limit = max(frame.top, (item.y - item.font * 12f).roundToInt())
                    while (upper > limit && grid.dark[(upper - 1) * grid.width + x].toInt() != 0) upper--
                    return SideBody(upper, (x + direction * 3).toFloat().coerceIn(frame.left.toFloat(), frame.right.toFloat()))
                }
            }
        }
        return null
    }

    private data class TextEdge(val index: Int, val x: Float, val top: Float, val bottom: Float, val font: Float)

    /** Repair only clearly aligned vertical rows; never infer a whole-page turn from text alone. */
    private fun alignedTextFallback(
        page: PdfPageText,
        previous: List<AbInstalledPosition>,
        checkCancelled: () -> Unit,
    ): List<AbInstalledPosition> {
        if (previous.size < 3) return previous
        val result = previous.toMutableList()
        for (region in previous.indices.groupBy { previous[it].region }.values) {
            checkCancelled()
            val boxes = region.mapNotNull { index ->
                val position = previous[index]
                val box = position.box
                val first = EplanParsing.rotate(box.x, box.y, page.width, page.height, position.rotation)
                val last = EplanParsing.rotate(box.right, box.bottom, page.width, page.height, position.rotation)
                val width = abs(last.first - first.first); val height = abs(last.second - first.second)
                val direction = ((box.angle + position.rotation) % 360 + 360) % 360
                if (direction !in 75..105 && direction !in 255..285 || height < max(width, box.fontSize) * 1.8f) null
                else TextEdge(index, (first.first + last.first) / 2f, min(first.second, last.second),
                    max(first.second, last.second), box.fontSize)
            }
            if (boxes.size < 3) continue
            val tolerance = max(.75f, boxes.map { it.font }.sorted()[boxes.size / 2] * .3f)
            val candidates = listOf<(TextEdge) -> Float>({ it.top }, { it.bottom }).flatMap { edge ->
                val groups = mutableListOf<MutableList<TextEdge>>()
                for (box in boxes.sortedBy(edge)) {
                    val group = groups.lastOrNull()
                    if (group != null && abs(edge(box) - edge(group.first())) <= tolerance) group += box
                    else groups += mutableListOf(box)
                }
                groups.filter { group -> group.size >= 3 &&
                    group.maxOf { it.x } - group.minOf { it.x } >= tolerance * 8f &&
                    group.maxOf { it.bottom - it.top } - group.minOf { it.bottom - it.top } >= tolerance * 2f }
            }.sortedByDescending { it.size }
            val used = mutableSetOf<Int>()
            for (candidate in candidates) {
                checkCancelled()
                if (candidate.any { it.index in used }) continue
                val slots = candidate.map { it.index }.sorted()
                val ordered = candidate.sortedBy { it.x }
                slots.forEachIndexed { offset, index -> result[index] = previous[ordered[offset].index] }
                used.addAll(slots)
            }
        }
        return result
    }

    private fun overlap(first: Band, second: Band) = max(0, min(first.bottom, second.bottom) - max(first.top, second.top) + 1)
    private fun point(x: Int, y: Int, width: Int, height: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        90 -> height - 1 - y to x
        180 -> width - 1 - x to height - 1 - y
        270 -> y to width - 1 - x
        else -> x to y
    }
    private fun turn(rect: Rect, width: Int, height: Int, rotation: Int): Rect {
        val a = point(rect.left, rect.top, width, height, rotation)
        val b = point(rect.right, rect.bottom, width, height, rotation)
        return Rect(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second), rect.count)
    }
    private fun turn(grid: Grid, rotation: Int, checkCancelled: () -> Unit): Grid {
        if (rotation == 0) return grid
        val width = if (rotation % 180 == 0) grid.width else grid.height
        val height = if (rotation % 180 == 0) grid.height else grid.width
        val colors = ByteArray(grid.colors.size)
        val dark = ByteArray(grid.dark.size)
        for (y in 0 until grid.height) {
            checkCancelled()
            for (x in 0 until grid.width) {
                val p = point(x, y, grid.width, grid.height, rotation)
                colors[p.second * width + p.first] = grid.colors[y * grid.width + x]
                dark[p.second * width + p.first] = grid.dark[y * grid.width + x]
            }
        }
        return Grid(width, height, colors, dark)
    }
}
