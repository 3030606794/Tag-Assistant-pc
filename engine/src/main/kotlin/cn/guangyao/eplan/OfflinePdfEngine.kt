package cn.guangyao.eplan

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import org.apache.pdfbox.pdmodel.PDPageTree
import java.io.IOException
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import java.io.StringWriter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Local native-text extraction. No network, cloud inference, or invented OCR results. */
class OfflinePdfEngine(
    private val cacheDirectory: File,
    private val layoutRasterSource: LayoutRasterSource? = null,
) {
    private class Cancelled : RuntimeException()
    private data class Installed(val scope: PdfPageScope, val box: PdfTextBox, val id: String, val rotation: Int, val region: Int = 0)
    private data class CapacityEvidence(val cores: Int? = null, val ambiguous: Boolean = false, val unverified: Boolean = false)
    private data class WireSource(val scope: PdfPageScope, val box: PdfTextBox, val wire: Cn1Wire, val capacity: CapacityEvidence)

    fun extract(
        document: ImportedDocument,
        mode: ExtractionMode,
        rules: RuleSettings,
        onProgress: (JobProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ExtractionResult {
        require(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "无法创建提取缓存目录。" }
        val source = File(document.path)
        require(source.isFile && source.canRead()) { "原始 PDF 已不可用，请重新导入。" }
        val installed = linkedMapOf<String, MutableList<Installed>>()
        val abInstalled = linkedMapOf<String, MutableList<Installed>>()
        val functions = linkedMapOf<String, MutableList<LabelRecord>>()
        val supplementaryResistorPages = mutableSetOf<Int>()
        val wires = linkedMapOf<String, MutableList<WireSource>>()
        val issues = mutableListOf<ExtractionIssue>()
        var pageCount = 0
        var completed = true
        var usableTextPages = 0
        val machineName = DocumentNames.machineName(document.name)
        fun checkCancelled() { if (isCancelled()) throw Cancelled() }
        fun issue(message: String, page: Int = 0, layout: String = "") {
            val item = ExtractionIssue(message, page, layout)
            if (item !in issues) issues.add(item)
        }
        fun progress(fraction: Float, message: String, page: Int = 0) {
            onProgress(JobProgress(true, fraction.coerceIn(0f, .99f), message, page, pageCount))
        }

        try {
            progress(0f, "正在打开本地图纸")
            IncrementalPdfDocument(source, cacheDirectory, isCancelled).use { session ->
                val pdf = session.pdf
                pageCount = pdf.numberOfPages
                require(pageCount > 0) { "PDF 没有可读取的页面。" }
                val cache = PageCache(cacheDirectory, document, source.lastModified())
                val orderCache = AbOrderCache(cacheDirectory, document, source.lastModified())
                fun read(page: Int): PdfPageText {
                    checkCancelled()
                    return cache.read(page) ?: try {
                        PositionedStripper(isCancelled).read(session.preparePage(page), page).also { cache.write(it) }
                    } finally {
                        session.releasePage()
                    }
                }
                progress(.01f, "读取图纸目录与电柜范围")
                val scopes = indexPages(pdf, ::checkCancelled) { count -> progress(.015f, "整理页面目录 · $count 页") }.toMutableMap()

                val missing = (1..pageCount).filter { page -> scopes[page]?.let { it.cabinet.isBlank() || it.type.isBlank() } ?: true }
                if (scopes.isEmpty()) issue("PDF 没有可用页面目录，正在逐页识别类型与所属电柜；无法确认归属的页面会列入疑点。")
                for ((index, page) in missing.withIndex()) {
                    checkCancelled()
                    progress(.02f + .10f * (index.toFloat() / max(1, missing.size)), "识别未索引页面 ${index + 1}/${missing.size}", page)
                    val data = read(page)
                    val scope = scopeFromFrame(data)
                    val indexed = scopes[page]
                    if (scope != null) scopes[page] = if (indexed == null) scope else indexed.copy(
                        cabinet = indexed.cabinet.ifBlank { scope.cabinet },
                        higher = indexed.higher.ifBlank { scope.higher },
                        type = indexed.type.ifBlank { scope.type },
                        title = indexed.title.ifBlank { scope.title },
                    )
                    else if (data.boxes.isEmpty()) issue("页面没有可提取文字，当前离线版不能识别扫描图片。", page)
                    else if (scopes.isEmpty() || missing.size > pageCount / 2) issue("未能确认页面类型和电柜归属，未将该页设备归入其他电柜。", page)
                }
                val sortedScopes = scopes.values.sortedBy { it.page }
                val duplicates = sortedScopes.filter { it.cabinet.isNotBlank() }.groupBy { it.cabinet }
                    .filterValues { list -> list.map { it.higher }.filter { it.isNotBlank() }.distinct().size > 1 }.keys
                val normalized = sortedScopes.map { scope ->
                    if (scope.cabinet in duplicates) scope.copy(cabinet = scope.higher.removePrefix("=") + "+" + scope.cabinet) else scope
                }
                if (duplicates.isNotEmpty()) issue("不同高层代号存在同名电柜，已保留高层代号区分分组：${duplicates.joinToString()}。")
                normalized.filter { it.cabinet.isBlank() && it.type.isNotBlank() &&
                    (EplanParsing.isLayout(it) || EplanParsing.isWiring(it)) }.forEach {
                    issue("页面类型已识别但未能确认电柜归属，未归入其他电柜。", it.page)
                }
                val selected = normalized.filter { it.cabinet.isNotBlank() && EplanParsing.acceptsCabinet(it.cabinet, rules) }
                val layouts = if (EplanParsing.needsLayout(mode)) selected.filter(EplanParsing::isLayout) else emptyList()
                val schematic = if (EplanParsing.needsCn1(mode)) selected.filter(EplanParsing::isWiring) else emptyList()
                val planSize = max(1, layouts.size + schematic.size)
                var done = 0
                fun reading(scope: PdfPageScope, action: String): PdfPageText {
                    progress(.13f + .78f * done.toFloat() / planSize, "$action · ${scope.cabinet} · ${done + 1}/$planSize", scope.page)
                    done++
                    return read(scope.page).also { if (it.boxes.isNotEmpty()) usableTextPages++ }
                }
                if (EplanParsing.needsLayout(mode) && layouts.isEmpty()) issue("未找到符合条件的实际安装布局页。可检查电柜筛选，或使用带 EPLAN 页面目录和原生文字的 PDF。")
                if (EplanParsing.needsCn1(mode) && schematic.isEmpty()) issue("未找到可确认归属的原理图或接线页，CN1 未生成空分组。")
                val rotations = EplanParsing.overrides(rules.rotationOverrides)
                val manualFunctions = EplanParsing.overrides(rules.functionOverrides)
                for (scope in layouts) {
                    val legacyPage = reading(scope, "读取实际安装位置")
                    val useNativeLayout = mode in setOf(ExtractionMode.FULL, ExtractionMode.DEVICE, ExtractionMode.FUNCTION, ExtractionMode.QUANTITY)
                    val page = if (useNativeLayout) legacyPage.copy(boxes = legacyPage.layoutBoxes ?: legacyPage.boxes) else legacyPage
                    if (page.boxes.isEmpty()) {
                        issue("布局页没有可提取文字，未生成设备；请使用原生文字 PDF。", page.page, scope.cabinet)
                        continue
                    }
                    // Read only rectangles on this layout. Destinations never supply A/B text.
                    // A/B use native runs; C/E retain the original view and candidate rules.
                    val links = try { session.readPageLinks(scope.page) }
                    catch (cancelled: IncrementalPdfDocument.CancelledException) { throw cancelled }
                    catch (_: IOException) {
                        issue("此布局的部分器件区域无法读取，已继续按布局文字位置处理。", scope.page, scope.cabinet)
                        emptyList()
                    } finally { session.releasePage() }
                    val linkAreas = links.map { it.source.asBox() }.distinct()
                    val legacyDevices = AbLayoutAnalysis.layoutDevices(legacyPage, linkAreas, ::checkCancelled)
                    val devices = if (useNativeLayout) AbLayoutAnalysis.layoutDevices(page, linkAreas, ::checkCancelled,
                        recoverUnmarkedIds = true) else legacyDevices
                    val override = rotations["PAGE:${scope.page}"] ?: rotations[scope.cabinet.uppercase()]
                    val rotation = override?.toIntOrNull()?.takeIf { it in setOf(0, 90, 180, 270) }
                    val legacyInstalled = AbLayoutAnalysis.installedPositions(legacyPage, legacyDevices, rotation, ::checkCancelled)
                        .mapNotNull { position -> EplanParsing.deviceId(position.box.text)?.let {
                            Installed(scope, position.box, it, position.rotation, position.region)
                        } }
                    installed.getOrPut(scope.cabinet) { mutableListOf() }.addAll(legacyInstalled)
                    if (devices.isEmpty()) {
                        issue("布局页未找到可确认的实际安装设备编号。", page.page, scope.cabinet)
                        continue
                    }
                    val ordered = AbLayoutAnalysis.installedPositions(page, devices, rotation, ::checkCancelled)
                    val pageInstalled = ordered.mapNotNull { position -> EplanParsing.deviceId(position.box.text, requireLeader = false)?.let {
                        Installed(scope, position.box, it, position.rotation, position.region)
                    } }
                    if (AbLabelRules.supplementaryResistors(scope, pageInstalled.map { it.id })) supplementaryResistorPages += scope.page
                    // Keep the original order for C/E and the count-only mode. A/B get their own
                    // permutation; physical instance IDs and source coordinates never move.
                    val orderAb = mode in setOf(ExtractionMode.FULL, ExtractionMode.DEVICE, ExtractionMode.FUNCTION)
                    val abPageInstalled = if (orderAb) {
                        checkCancelled()
                        var geometryFailure: String? = null
                        val plan = orderCache.read(page, devices, rotation) ?: try {
                            progress(.13f + .78f * (done - 1).toFloat() / planSize,
                                "分析布局方向与安装行 · ${scope.cabinet}", scope.page)
                            val raster = if (layoutRasterSource != null) {
                                layoutRasterSource.read(document, scope.page, ::checkCancelled)
                            } else {
                                EmbeddedLayoutRaster.read(session, scope.page, ::checkCancelled)
                                    // Ordinary PDFBox loading expands catalog metadata. Large xref
                                    // documents must keep the incremental bounded-memory contract.
                                    ?: if (session.pdf.document.xrefTable.size <= 200_000)
                                        DesktopRasterSource(cacheDirectory).read(document, scope.page, ::checkCancelled)
                                    else null
                            }
                            checkCancelled()
                            AbInstallationOrder.order(page, devices, raster, rotation, ::checkCancelled).also {
                                checkCancelled()
                                orderCache.write(page, devices, rotation, it)
                            }
                        } catch (error: Cancelled) { throw error }
                        catch (error: IncrementalPdfDocument.CancelledException) { throw error }
                        catch (_: OutOfMemoryError) { geometryFailure = "当前页面图像处理内存不足"; null }
                        catch (_: LinkageError) { geometryFailure = "设备图像读取组件不可用"; null }
                        catch (error: Exception) { geometryFailure = error.message ?: "布局图像读取失败"; null }
                        if (plan?.confident != true) {
                            issue((geometryFailure?.let { "$it。" } ?: "") +
                                "此布局的安装行结构未能可靠识别，三列与单列沿用文字位置排序。", scope.page, scope.cabinet)
                        } else {
                            val turn = plan.positions.firstOrNull()?.rotation ?: 0
                            issue((if (turn == 0) "布局方向：保持原方向" else "布局方向：顺时针旋转 ${turn}°") +
                                "；三列与单列按安装行从左到右、从上到下排列。", scope.page, scope.cabinet)
                        }
                        val byBox = pageInstalled.associateBy { it.box }
                        val sorted = plan?.positions?.mapNotNull { position ->
                            byBox[position.box]?.copy(rotation = position.rotation)
                        }
                        if (sorted != null && sorted.size == pageInstalled.size && sorted.map { it.box }.toSet().size == pageInstalled.size) sorted
                        else pageInstalled
                    } else pageInstalled
                    abInstalled.getOrPut(scope.cabinet) { mutableListOf() }.addAll(abPageInstalled)
                    if (EplanParsing.needsFunction(mode)) {
                        val owners = pageInstalled.mapNotNull { device ->
                            checkCancelled()
                            val owner = linksForDevice(links, device.box).map { it.source.asBox() }.filter { bounds ->
                                bounds.width * bounds.height > device.box.width * device.box.height * 2f &&
                                    bounds.width < page.width * .6f && bounds.height < page.height * .6f &&
                                    devices.count { contains(bounds, it) } == 1 &&
                                    page.boxes.any { it.blue && EplanParsing.isFunction(it.text) && contains(bounds, it) }
                            }.minByOrNull { it.width * it.height }
                            owner?.let { device.box to it }
                        }.toMap()
                        val mapped = AbLayoutAnalysis.associateFunctions(page, devices, owners,
                            checkCancelled = ::checkCancelled, linkAreas = linkAreas)
                        for (device in abPageInstalled) {
                            checkCancelled()
                            val manual = manualFunctions["${scope.cabinet}/${device.id}".uppercase()]
                                ?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                            val direct = orderFunctions(mapped[device.box].orEmpty(), page, device.rotation).map { function ->
                                LayoutFuseRatings.decorate(page, device.id, function)
                            }
                            val fallback = AbLabelRules.missingModuleCaption(device.id)
                            val found = when {
                                manual.isNotEmpty() -> manual.map { AbFunctionEvidence(it, device.box, scope.page) }
                                direct.isNotEmpty() -> direct
                                fallback != null -> listOf(AbFunctionEvidence(fallback, device.box, scope.page))
                                else -> emptyList()
                            }
                            if (found.isEmpty()) {
                                issue("${device.id} 未在布局图中找到可靠对应的功能文字，且不属于已指定补名的五类模块，单列未填入其他页面文字。", scope.page, scope.cabinet)
                                continue
                            }
                            found.forEach { f ->
                                functions.getOrPut(scope.cabinet) { mutableListOf() }.add(
                                    record(LabelColumn.FUNCTION, scope.cabinet, AbLayoutAnalysis.normalizeAbDisplayText(f.text), f.page, f.box).copy(
                                        installationId = installationId(document.id, device), deviceId = device.id,
                                        evidence = when {
                                            manual.isNotEmpty() -> "rule-override"
                                            direct.isNotEmpty() -> "installation-label"
                                            else -> "user-module-label"
                                        },
                                    )
                                )
                            }
                        }
                    }
                }
                // Other modes still read their original CN1 sources; A/B never use this evidence.
                for (scope in schematic) {
                    val page = reading(scope, "读取原理图与完整线号")
                    if (page.boxes.isEmpty()) {
                        issue("原理图页面没有可提取文字，不能确认本页 CN1。", scope.page, scope.cabinet)
                        continue
                    }
                    val valid = EplanParsing.readingOrder(page.boxes.filter { it.text.contains(":CN1:", true) }, page.width, page.height)
                    for (box in valid) {
                        val wire = EplanParsing.cn1Wire(box.text) ?: continue
                        val capacity = nearbyCapacity(page, box, wire.um)
                        wires.getOrPut(scope.cabinet) { mutableListOf() }.add(WireSource(scope, box, wire, capacity))
                    }
                }
                if (usableTextPages == 0) issue("本次范围内未读取到原生文字。当前离线版支持原生文字 PDF，扫描件需要先完成 OCR。")
            }
        } catch (_: IncrementalPdfDocument.CancelledException) {
            completed = false
            issue("任务已取消，保留已完成页面的数据；重新提取会复用本地页面缓存。")
        } catch (_: Cancelled) {
            completed = false
            issue("任务已取消，保留已完成页面的数据；重新提取会复用本地页面缓存。")
        }

        val records = mutableListOf<LabelRecord>()
        val wanted = if (mode == ExtractionMode.QUANTITY) setOf(LabelColumn.DEVICE, LabelColumn.FUNCTION, LabelColumn.CN1)
            else mode.columns.toSet()
        fun group(column: LabelColumn, layout: String, values: List<LabelRecord>) {
            if (values.isEmpty()) return
            val first = values.first()
            records += record(column, layout, layout, first.page, kind = RecordKind.GROUP)
            records += record(column, layout, machineName, first.page, kind = RecordKind.FILE_LABEL)
            records.addAll(values)
        }
        for ((layout, devices) in installed) {
            if (LabelColumn.DEVICE in wanted) group(LabelColumn.DEVICE, layout, abInstalled[layout].orEmpty()
                .sortedBy { it.scope.page in supplementaryResistorPages }.map {
                record(LabelColumn.DEVICE, layout, it.id, it.scope.page, it.box)
                    .copy(installationId = installationId(document.id, it), deviceId = it.id, evidence = "installation-position")
            })
            if (LabelColumn.YELLOW in wanted) group(LabelColumn.YELLOW, layout, devices.filter { EplanParsing.isUm(it.id) }.map { record(LabelColumn.YELLOW, layout, it.id, it.scope.page, it.box) })
            if (LabelColumn.FIXTURE in wanted) group(LabelColumn.FIXTURE, layout, devices.filter { EplanParsing.isXt(it.id) }.distinctBy { it.id }.map { record(LabelColumn.FIXTURE, layout, it.id, it.scope.page, it.box) })
            if (LabelColumn.FUNCTION in wanted) group(LabelColumn.FUNCTION, layout,
                functions[layout].orEmpty().sortedBy { it.page in supplementaryResistorPages })
        }
        if (LabelColumn.CN1 in wanted) for ((layout, sources) in wires) {
            val values = mutableListOf<LabelRecord>()
            for ((um, list) in sources.groupBy { it.wire.um }) {
                val first = list.first()
                val capacities = list.mapNotNull { it.capacity.cores }.distinct()
                val capacityAmbiguous = list.any { it.capacity.ambiguous } || capacities.size > 1
                val capacity = if (capacities.size == 1) capacities.single() else rules.defaultCn1Cores.coerceIn(1, 100) * EplanParsing.umAxisCount(um)
                values += record(LabelColumn.CN1, layout, um, first.scope.page, first.box, RecordKind.UM_HEADER)
                    .copy(cn1Capacity = capacity.takeIf { completed && !capacityAmbiguous })
                list.forEach { values += record(LabelColumn.CN1, layout, it.wire.text, it.scope.page, it.box) }
                if (rules.addSpares && completed) {
                    if (capacities.isEmpty() && list.any { it.capacity.unverified }) {
                        issue("$um 附近有线缆芯数，但未能确认属于此 CN1；备用按规则默认的 $capacity 芯计算，可查看图纸并调整默认值。", first.scope.page, layout)
                    }
                    if (capacityAmbiguous) issue("$um 附近出现不同芯数标注，未自动补备用，请核对线缆归属。", first.scope.page, layout)
                    else if (list.size > capacity) issue("$um 有 ${list.size} 条完整号码，超过 $capacity 芯；已保留全部号码并停止补备用。", first.scope.page, layout)
                    else repeat(capacity - list.size) { values += record(LabelColumn.CN1, layout, "备用", first.scope.page, first.box, RecordKind.SPARE) }
                }
            }
            group(LabelColumn.CN1, layout, values)
        }
        if (mode == ExtractionMode.FULL || mode == ExtractionMode.QUANTITY) records += XlsxExporter.buildQuantityRecords(records)
        if (records.none { it.kind == RecordKind.LABEL && it.column != LabelColumn.QUANTITY }) issue("没有得到可导出的实际标签；请查看疑点和筛选范围。")
        return ExtractionResult(UUID.randomUUID().toString(), document.id, document.name, mode, System.currentTimeMillis(), records, issues, pageCount, completed, rules)
    }

    private fun record(column: LabelColumn, layout: String, text: String, page: Int, box: PdfTextBox? = null, kind: RecordKind = RecordKind.LABEL) = LabelRecord(
        UUID.randomUUID().toString(), column, layout, text, page, box?.x ?: 0f, box?.y ?: 0f,
        box?.width ?: 0f, box?.height ?: 0f, kind, box?.text ?: text,
    )

    private fun physicalKey(device: Installed) = "${device.scope.higher}/${device.scope.cabinet}/${device.scope.page}/${device.region}:${device.box.x}:${device.box.y}:${device.id}"
    private fun installationId(documentId: String, device: Installed) = UUID.nameUUIDFromBytes("$documentId/${physicalKey(device)}".toByteArray(Charsets.UTF_8)).toString()

    private fun orderFunctions(values: List<AbFunctionEvidence>, page: PdfPageText, rotation: Int): List<AbFunctionEvidence> {
        val drawingRotation = (rotation + page.rotation) % 360
        if (values.size < 2 || drawingRotation == 0) return values
        val width = if (page.rotation % 180 == 0) page.width else page.height
        val height = if (page.rotation % 180 == 0) page.height else page.width
        val anchored = values.associateBy {
            val point = EplanParsing.rotate(it.box.baselineX, it.box.baselineY, page.width, page.height,
                (360 - page.rotation) % 360)
            it.box.copy(x = point.first, y = point.second, width = 0f, height = 0f)
        }
        // A changed reading direction reorders separate captions, never characters within one.
        return EplanParsing.readingOrder(anchored.keys.toList(), width, height, drawingRotation).map { anchored.getValue(it) }
    }

    private fun indexPages(pdf: PDDocument, cancelled: () -> Unit, progress: (Int) -> Unit): Map<Int, PdfPageScope> {
        val outline = pdf.documentCatalog.documentOutline ?: return emptyMap()
        val pageNumbers = IdentityHashMap<COSDictionary, Int>()
        pdf.pages.forEachIndexed { index, page -> pageNumbers[page.cosObject] = index + 1 }
        val result = linkedMapOf<Int, PdfPageScope>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun walk(first: PDOutlineItem?, inherited: PdfPageScope, depth: Int) {
            if (depth > 80) throw IOException("PDF 页面目录层级异常复杂，已安全停止。")
            var item = first
            while (item != null) {
                val current = item
                cancelled()
                if (!visited.add(current.cosObject)) break
                val title = current.title.orEmpty().trim()
                if (Regex("^(设备树|设备列表|Devices?\\b)", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
                    (depth == 0 && title == "页列表" && result.isNotEmpty())) { item = item.nextSibling; continue }
                var state = inherited
                if (title.startsWith("=")) state = inherited.copy(higher = title.substringBefore(' '), cabinet = "", type = "")
                if (title.startsWith("+")) state = inherited.copy(cabinet = title.substringBefore(' ').removePrefix("+"), type = "")
                if (title.startsWith("&")) state = inherited.copy(type = title.substringBefore(' ').removePrefix("&"))
                val isPageTitle = !title.startsWith("=") && !title.startsWith("+") && !title.startsWith("&") &&
                    (state.type.isNotBlank() || Regex("布局|原理|接线|功能文本").containsMatchIn(title))
                if (isPageTitle) {
                    val destination = runCatching { current.findDestinationPage(pdf) }.getOrNull()
                    val page = destination?.let { pageNumbers[it.cosObject] }
                    if (page != null) {
                        if (result[page]?.cabinet.isNullOrBlank() || state.cabinet.isNotBlank()) {
                            result[page] = state.copy(page = page, title = title.replaceFirst(Regex("^\\d+(?:[.][A-Za-z0-9]+)?\\s+"), ""))
                        }
                        if (result.size % 100 == 0) progress(result.size)
                    }
                }
                walk(item.firstChild, state, depth + 1)
                item = item.nextSibling
            }
        }
        walk(outline.firstChild, PdfPageScope(0), 0)
        return result
    }

    private fun scopeFromFrame(page: PdfPageText): PdfPageScope? {
        val frame = page.boxes.filter { it.y > page.height * .78f }
        val type = frame.firstNotNullOfOrNull { Regex("&([A-Z]{2,4}[A-Z0-9]+)").find(it.text)?.groupValues?.get(1) }.orEmpty()
        val cabinet = frame.firstNotNullOfOrNull { Regex("^\\+([^\\s/&]+)$").matchEntire(it.text)?.groupValues?.get(1) }.orEmpty()
        val higher = frame.firstOrNull { Regex("^=[A-Za-z0-9_.-]+$").matches(it.text) }?.text.orEmpty()
        val title = frame.firstNotNullOfOrNull { Regex("页描述[:：]\\s*(.+)").find(it.text)?.groupValues?.get(1) }
            ?: frame.firstOrNull { it.text.contains("页描述") }?.let { anchor -> frame.filter { it.x >= anchor.right - 1f && abs(it.cy - anchor.cy) < anchor.fontSize * 1.5 }.minByOrNull { it.x }?.text }
            ?: ""
        return if (type.isBlank() && title.isBlank() && cabinet.isBlank()) null else PdfPageScope(page.page, cabinet, higher, type, title)
    }

    private fun IncrementalPdfDocument.Rect.asBox() = PdfTextBox("", x, y, width, height)
    private fun contains(bounds: PdfTextBox, box: PdfTextBox) = box.cx >= bounds.x - .5f && box.cx <= bounds.right + .5f &&
        box.cy >= bounds.y - .5f && box.cy <= bounds.bottom + .5f

    private fun linksForDevice(links: List<IncrementalPdfDocument.LinkEvidence>, device: PdfTextBox): List<IncrementalPdfDocument.LinkEvidence> {
        val matches = links.filter { contains(it.source.asBox(), device) }
        val smallestArea = matches.minOfOrNull { it.source.width * it.source.height } ?: return emptyList()
        // A broad clickable group must not associate every child with an unrelated destination.
        return matches.filter { it.source.width * it.source.height <= smallestArea * 1.1f + 1f }
    }

    private fun nearbyCapacity(page: PdfPageText, wire: PdfTextBox, um: String): CapacityEvidence {
        val pattern = Regex("(?:^|[^0-9])([0-9]{1,3})\\s*(?:芯|[x×*]\\s*[0-9]+[.][0-9]+)")
        val exactUm = Regex("(?<![A-Za-z0-9])${Regex.escape(um)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        val onlyUm = page.boxes.mapNotNull { EplanParsing.deviceId(it.text) }.filter(EplanParsing::isUm).distinct().singleOrNull()
        val candidates = page.boxes.mapNotNull { box ->
            val count = pattern.find(box.text)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (count !in 1..100 || distance(wire, box) > 80f) null else box to count
        }.sortedBy { distance(wire, it.first) }
        // A nearby cable may be power, brake, or STO. Require an explicit CN1 association before overriding the rule.
        val values = candidates.filter { (box, _) -> box.text.contains("CN1", true) &&
            (exactUm.containsMatchIn(box.text) || onlyUm.equals(um, true)) }.map { it.second }.distinct()
        return CapacityEvidence(values.singleOrNull(), values.size > 1, candidates.isNotEmpty() && values.isEmpty())
    }

    private fun distance(a: PdfTextBox, b: PdfTextBox): Float {
        val dx = max(0f, max(a.x - b.right, b.x - a.right))
        val dy = max(0f, max(a.y - b.bottom, b.y - a.bottom))
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private class PositionedStripper(private val cancelled: () -> Boolean) : PDFTextStripper() {
        init {
            // PDFTextStripper's legacy engine does not register color operators. Without these
            // every glyph appears black, silently discarding all blue installation functions.
            addOperator(SetNonStrokingDeviceRGBColor())
            addOperator(SetNonStrokingDeviceCMYKColor())
            addOperator(SetNonStrokingDeviceGrayColor())
            addOperator(SetNonStrokingColorSpace())
            addOperator(SetNonStrokingColor())
            addOperator(SetNonStrokingColorN())
        }
        private class PageLimit(message: String) : IOException(message)
        private val glyphs = mutableListOf<PdfTextBox>()
        private var targetPage = 0
        private var operators = 0
        private var nextTextObject = 0
        private var nextTextRun = 0
        private var textObject = -1
        private var textRun = -1
        fun read(document: PDDocument, page: Int): PdfPageText {
            // Process one page directly instead of walking the complete page tree for every read.
            // The superclass page counter stays at zero because processPages below avoids that loop.
            targetPage = page - 1
            startPage = 0
            endPage = 0
            setShouldSeparateByBeads(false)
            sortByPosition = false
            suppressDuplicateOverlappingText = false
            writeText(document, StringWriter())
            val pdPage = document.getPage(page - 1)
            val rotated = pdPage.rotation % 180 != 0
            val width = if (rotated) pdPage.cropBox.height else pdPage.cropBox.width
            val height = if (rotated) pdPage.cropBox.width else pdPage.cropBox.height
            return PdfPageText(page, width, height, EplanParsing.joinGlyphs(glyphs),
                EplanParsing.joinNativeGlyphs(glyphs) { if (cancelled()) throw Cancelled() },
                (pdPage.rotation % 360 + 360) % 360)
        }

        override fun processPages(pages: PDPageTree) {
            if (cancelled()) throw Cancelled()
            processPage(pages[targetPage])
        }

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
            // PDFBox expands quote text operators through processOperator("T*", null).
            // Preserve the library's nullable operand contract for these zero-argument calls.
            if (++operators % 256 == 0 && cancelled()) throw Cancelled()
            if (operators > 2_000_000) throw PageLimit("单页绘图指令超过安全处理范围，已停止；请简化或拆分此页后重试。")
            val previousObject = textObject
            val previousRun = textRun
            if (operator.name == "BT") textObject = nextTextObject++
            // Keep each invocation distinct, including reused Form XObjects and quote operators.
            // Glyph callbacks happen synchronously inside the corresponding show operation.
            when (operator.name) { "Tj", "TJ", "'", "\"" -> textRun = nextTextRun++ }
            try {
                super.processOperator(operator, operands)
            } finally {
                textRun = previousRun
                if (operator.name == "ET") textObject = -1
                if (operator.name == "Do") textObject = previousObject
            }
        }

        override fun operatorException(operator: Operator, operands: MutableList<COSBase>?, error: IOException) {
            // A limit reached inside a nested Form /Do must fail the page, never cache a truncated
            // result. Keep PDFBox's existing tolerance for unrelated recoverable drawing warnings.
            if (error is PageLimit) throw error
            super.operatorException(operator, operands, error)
        }

        override fun increaseLevel() {
            if (level >= 48) throw PageLimit("单页图形嵌套过深，已安全停止；请简化此页后重试。")
            super.increaseLevel()
        }

        override fun saveGraphicsState() {
            if (graphicsStackSize >= 256) throw PageLimit("单页图形状态嵌套过深，已安全停止；请简化此页后重试。")
            super.saveGraphicsState()
        }

        override fun processTextPosition(text: TextPosition) {
            if (glyphs.size % 256 == 0 && cancelled()) throw Cancelled()
            if (glyphs.size >= 150_000) throw PageLimit("单页文字超过 15 万个字符，已停止；请拆分此页后重试。")
            val value = text.unicode ?: return
            if (value.isEmpty()) return
            val matrix = text.textMatrix
            val rawAngle = Math.toDegrees(atan2(matrix.shearY.toDouble(), matrix.scaleX.toDouble()))
            val screenAngle = EplanParsing.normalizeAngle(text.rotation - rawAngle)
            val angle = Math.toRadians(screenAngle.toDouble())
            val dx = cos(angle).toFloat(); val dy = sin(angle).toFloat()
            val advance = hypot((text.endX - matrix.translateX).toDouble(), (text.endY - matrix.translateY).toDouble()).toFloat().coerceAtLeast(.01f)
            // A 45-degree baseline must not shrink the inferred font by another sqrt(2).
            val scale = hypot(matrix.scaleX.toDouble(), matrix.shearY.toDouble()).toFloat()
            val font = max(text.heightDir, scale * .65f).coerceAtLeast(.5f)
            val bx = text.x; val by = text.y
            val xs = listOf(bx, bx + dx * advance, bx + dy * font, bx + dx * advance + dy * font)
            val ys = listOf(by, by + dy * advance, by - dx * font, by + dy * advance - dx * font)
            val color = runCatching { graphicsState.nonStrokingColor.toRGB() }.getOrDefault(0)
            val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
            val blue = b > r + 35 && b > g + 20 && b > 70
            glyphs += PdfTextBox(value, xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min(), bx, by,
                screenAngle, font, blue, advance, textObject, textRun, glyphs.size)
            // Keep both the established geometry view and native run provenance for A/B only.
        }
    }

    private class PageCache(cacheDirectory: File, document: ImportedDocument, modified: Long) {
        // v6 also retains PDF display rotation so A/B property matching uses drawing coordinates.
        private val directory = File(cacheDirectory, "offline-pdf/${document.id.filter { it.isLetterOrDigit() || it == '-' }}-${document.bytes}-$modified-desktop-pdfbox2-v6").apply { mkdirs() }
        fun read(page: Int): PdfPageText? = try {
            val file = File(directory, "$page.json")
            if (!file.isFile || file.length() > 8L * 1024 * 1024) null
            else JsonReader(file.bufferedReader()).use { input ->
                var width = 0f; var height = 0f
                var rotation = -1
                var boxes = emptyList<PdfTextBox>()
                var layoutBoxes: List<PdfTextBox>? = null
                fun readBoxes(): List<PdfTextBox> {
                    val values = mutableListOf<PdfTextBox>()
                    input.beginArray()
                    while (input.hasNext()) {
                        require(values.size < 50_000) { "缓存页过大" }
                        input.beginArray()
                        values += PdfTextBox(input.nextString(), input.nextDouble().toFloat(), input.nextDouble().toFloat(),
                            input.nextDouble().toFloat(), input.nextDouble().toFloat(), input.nextDouble().toFloat(),
                            input.nextDouble().toFloat(), input.nextInt(), input.nextDouble().toFloat(), input.nextBoolean(),
                            input.nextDouble().toFloat(), input.nextInt(), input.nextInt(), input.nextInt(), input.nextInt())
                        input.endArray()
                    }
                    input.endArray()
                    return values
                }
                input.beginObject()
                while (input.hasNext()) when (input.nextName()) {
                    "width" -> width = input.nextDouble().toFloat()
                    "height" -> height = input.nextDouble().toFloat()
                    "rotation" -> rotation = input.nextInt()
                    "boxes" -> boxes = readBoxes()
                    "layoutBoxes" -> layoutBoxes = readBoxes()
                    else -> input.skipValue()
                }
                input.endObject()
                require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
                // Missing native view cannot silently revive the original overlapping-text bug.
                require(layoutBoxes != null)
                require(rotation in setOf(0, 90, 180, 270))
                PdfPageText(page, width, height, boxes, layoutBoxes, rotation)
            }
        } catch (_: Exception) { null }

        fun write(page: PdfPageText) {
            if (page.boxes.size > 50_000 || page.layoutBoxes == null || page.layoutBoxes.size > 50_000) return
            val target = File(directory, "${page.page}.json")
            val temp = File(directory, "${page.page}.tmp")
            try {
                JsonWriter(temp.bufferedWriter()).use { output ->
                    fun writeBoxes(values: List<PdfTextBox>) {
                        output.beginArray()
                        values.forEach { box ->
                            output.beginArray().value(box.text).value(box.x.toDouble()).value(box.y.toDouble()).value(box.width.toDouble())
                                .value(box.height.toDouble()).value(box.baselineX.toDouble()).value(box.baselineY.toDouble()).value(box.angle.toLong())
                                .value(box.fontSize.toDouble()).value(box.blue).value(box.advance.toDouble())
                                .value(box.sourceObject.toLong()).value(box.sourceRun.toLong()).value(box.sourceSequence.toLong())
                                .value(box.sourceEndSequence.toLong()).endArray()
                        }
                        output.endArray()
                    }
                    output.beginObject().name("width").value(page.width.toDouble()).name("height").value(page.height.toDouble())
                    output.name("rotation").value(page.rotation.toLong())
                    output.name("boxes"); writeBoxes(page.boxes)
                    output.name("layoutBoxes"); writeBoxes(page.layoutBoxes)
                    output.endObject()
                }
                if (temp.length() <= 8L * 1024 * 1024) CacheFiles.replace(temp, target)
            } catch (_: Exception) { /* A full/unavailable cache never invalidates extracted text. */ }
            finally { temp.delete() }
        }
    }
}
