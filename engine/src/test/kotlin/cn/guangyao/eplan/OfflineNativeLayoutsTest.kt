package cn.guangyao.eplan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * All 62 layout views from two independently audited drawings. Original native operators,
 * font metrics, images and local property rectangles survive; title blocks and actions do not.
 * Expected occurrence counts are the audited source census, not extractor-generated snapshots.
 * Run with the release decoder in cloud CI only; no local compilation or test execution required.
 */
class OfflineNativeLayoutsTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    @Test fun allFiftyCabinetsKeepTheirPhysicalOccurrencesIncludingBareCopperBarAndExtraResistors() {
        val expected = JSONObject(resource("native-layout-census.json"))
        for (prefix in listOf("b06-layouts", "survey-layouts")) withFixture(prefix) { document ->
            val result = extract(prefix, document, ExtractionMode.DEVICE)
            assertTrue(result.completed)
            val actual = labels(result, LabelColumn.DEVICE).groupBy { it.layout }
                .mapValues { (_, rows) -> rows.groupingBy { it.text }.eachCount() }
            val cabinets = expected.getJSONObject(prefix)
            assertEquals(cabinets.keys().asSequence().toSet(), actual.keys)
            for (cabinet in cabinets.keys()) {
                val counts = cabinets.getJSONObject(cabinet)
                val expectedCounts = counts.keys().asSequence().associateWith { counts.getInt(it) }
                assertEquals("$prefix/$cabinet occurrence census", expectedCounts, actual[cabinet])
            }
            assertEquals(if (prefix == "b06-layouts") 1032 else 1158, actual.values.sumOf { it.values.sum() })
        }
    }

    @Test fun b06C01UsesSixBandsStartingAtTheUnmarkedCopperBar() = withFixture("b06-layouts") { document ->
        val result = extract("b06-layouts", document, ExtractionMode.DEVICE, RuleSettings(cabinetFilter = "C01"))
        val devices = labels(result, LabelColumn.DEVICE)
        val bands = JSONArray(resource("b06-c01-order.json"))
        val expected = (0 until bands.length()).flatMap { i ->
            val row = bands.getJSONArray(i); (0 until row.length()).map { row.getString(it) }
        }
        assertEquals(dump(result), expected, devices.map { it.text })
        assertEquals("1XT01", devices.first().text)
        assertEquals("1XT01", devices.first().sourceText)
    }

    @Test fun b06C01KeepsFifteenSeparateSignalsAndElevenModuleCaptionsAcrossModesAndCache() = withFixture("b06-layouts") { document ->
        val result = extract("b06-layouts", document, ExtractionMode.FULL, RuleSettings(cabinetFilter = "C01"))
        val devices = labels(result, LabelColumn.DEVICE)
        val functions = labels(result, LabelColumn.FUNCTION)
        val signals = linkedMapOf(
            "1KA01" to listOf("1：急停辅助输出", "2：安全光栅辅助输出", "3：安全门禁辅助输出", "4：三段式开关辅助输出",
                "5：正常模式辅助输出", "6：维修模式辅助输出", "7：安全复位", "8：备用"),
            "1KA02" to listOf("1:冷水机启动", "2：除尘机启动"),
            "1KA03" to listOf("1：冷水机报警", "2：除尘机报警"),
            "1KA04" to listOf("1：NG缓存D调速电机1", "2：NG缓存D调速电机2", "3：NG缓存D调速电机3"),
        )
        signals.forEach { (id, captions) ->
            assertEquals("Independent signals for $id ${dump(result)}", captions, functions.filter { it.deviceId == id }.map { it.text })
        }
        val modules = linkedMapOf("ECT01" to "耦合器", "AI101" to "模拟量输入", "AI102" to "模拟量输入",
            "AI103" to "模拟量输入", "AI104" to "模拟量输入", "AI105" to "模拟量输入", "AO101" to "模拟量输出",
            "DI101" to "数字量输入", "DI102" to "数字量输入", "DO101" to "数字量输出", "DO102" to "数字量输出")
        assertEquals(modules.toList(), functions.filter { it.deviceId in modules }.map { it.deviceId to it.text })
        assertTrue(functions.filter { it.deviceId in modules }.all { it.evidence == "user-module-label" })
        val indices = devices.withIndex().associate { it.value.installationId to it.index }
        assertTrue(dump(result), functions.all { it.installationId in indices })
        val positions = functions.map { indices.getValue(it.installationId) }
        assertEquals("B follows installed A owners", positions.sorted(), positions)
        assertFalse(functions.any { it.text.startsWith('-') })
        val bOnly = extract("b06-layouts", document, ExtractionMode.FUNCTION, RuleSettings(cabinetFilter = "C01"))
        assertEquals(functions.map { Triple(it.installationId, it.deviceId, it.text) },
            labels(bOnly, LabelColumn.FUNCTION).map { Triple(it.installationId, it.deviceId, it.text) })
        val aOnly = extract("b06-layouts", document, ExtractionMode.DEVICE, RuleSettings(cabinetFilter = "C01"))
        assertEquals(devices.map { it.installationId }, labels(aOnly, LabelColumn.DEVICE).map { it.installationId })
        for ((mode, column) in listOf(ExtractionMode.YELLOW to LabelColumn.YELLOW, ExtractionMode.FIXTURE to LabelColumn.FIXTURE)) {
            val separate = extract("b06-layouts", document, mode, RuleSettings(cabinetFilter = "C01"))
            fun values(r: ExtractionResult) = labels(r, column).map { listOf(it.text, it.sourceText, it.x, it.y) }
            assertEquals("Legacy $column unchanged by A/B native spans", values(result), values(separate))
        }
    }

    @Test fun longResistorNamesFollowCommonPhysicalRowAndSupplementaryViewStaysAtCabinetEnd() = withFixture("b06-layouts") { document ->
        val result = extract("b06-layouts", document, ExtractionMode.DEVICE, RuleSettings(cabinetFilter = "C03 C04"))
        val c03 = "R82 R80/81 R79 R70 R69 R107 R106 R105 R103 R87/91 R119 R115 R114 R112 R109 R108".split(' ')
        val c04 = "R86 R84/85 R83 R75 R74 R104 R95/99 R118 R117 R116 R113 R111 R110".split(' ')
        assertEquals(dump(result), c03, labels(result, LabelColumn.DEVICE).filter { it.layout == "C03" }.map { it.text })
        val actual = labels(result, LabelColumn.DEVICE).filter { it.layout == "C04" }
        assertEquals(dump(result), c04, actual.take(13).map { it.text })
        assertEquals(c04.toSet(), actual.drop(13).map { it.text }.toSet())
        assertEquals(26, actual.size)
        assertEquals(List(13) { 4 } + List(13) { 5 }, actual.map { it.page })
        assertEquals(26, actual.map { it.installationId }.distinct().size)
    }

    @Test fun repeatedPowerTerminalsKeepTheirOwnPhaseCaptionsInsteadOfTheNextRailDevices() = withFixture("b06-layouts") { document ->
        val result = extract("b06-layouts", document, ExtractionMode.FUNCTION, RuleSettings(cabinetFilter = "C01"))
        val functions = labels(result, LabelColumn.FUNCTION)
        assertEquals(dump(result), listOf("N1", "L1", "L2", "L3").map { "【一】电源级端子-$it" },
            functions.filter { it.deviceId == "1XT10" }.map { it.text })
        assertEquals(dump(result), listOf("N1", "L1", "L2", "L3").map { "【三】级电源端子-$it" },
            functions.filter { it.deviceId == "1XT30" }.map { it.text })
        assertEquals(dump(result), listOf("N1", "L1", "L2", "L3").map { "【三】电源端子-$it" },
            functions.filter { it.deviceId == "1XT35" }.map { it.text })
        assertFalse(dump(result), functions.filter { it.deviceId.startsWith("1QF") }.any { it.text.contains("【三】电源端子") })
        assertEquals(dump(result), listOf("控制电源"), functions.filter { it.deviceId == "1FL01" }.map { it.text })
    }

    private fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")).bufferedReader().use { it.readText() }
    private fun labels(result: ExtractionResult, column: LabelColumn) = result.records.filter { it.column == column && it.kind == RecordKind.LABEL }
    private fun dump(result: ExtractionResult) = result.records.filter { it.kind == RecordKind.LABEL }
        .joinToString("\n", "\n") { "${it.column}/${it.layout}/${it.page}: ${it.deviceId} -> ${it.text} [${it.x},${it.y}]" }
    private fun extract(prefix: String, document: ImportedDocument, mode: ExtractionMode, rules: RuleSettings = RuleSettings()) =
        OfflinePdfEngine(cacheDirectory, LayoutRasterSource { _, page, check ->
            check()
            javaClass.getResourceAsStream("/fixtures/$prefix-p$page.png")?.use { input ->
                val image = requireNotNull(ImageIO.read(input))
                AbInstallationOrder.Raster(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
            }
        }).extract(document, mode, rules, {}, { false })
    private fun withFixture(prefix: String, block: (ImportedDocument) -> Unit) {
        val id = UUID.randomUUID().toString()
        val file = File(cacheDirectory, "$prefix-$id.pdf")
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$prefix-native.pdf")).use { input -> file.outputStream().use { input.copyTo(it) } }
        try { block(ImportedDocument(id, "$prefix.pdf", file.absolutePath, file.length(), 0L)) }
        finally { file.delete() }
    }
}
