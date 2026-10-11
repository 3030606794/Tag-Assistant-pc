package cn.guangyao.eplan

import org.apache.pdfbox.pdmodel.PDDocument
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression for the actual 0.6.3 phone exports: the PNG-injected tests passed while C01/C03
 * fell back to text order on the phone. Every extraction here uses the production defaults,
 * reading the layout image and its placement from the PDF itself. No PNG/raster injection.
 */
class OfflineEmbeddedGeometryTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    @Test fun bothDrawingsUseProductionGeometryAndKeepEveryAuditedPhysicalOccurrence() {
        val census = JSONObject(resource("native-layout-census.json"))
        for (prefix in listOf("b06-layouts", "survey-layouts")) withFixture(prefix) { document ->
            val result = extract(document, ExtractionMode.FULL)
            val actual = labels(result, LabelColumn.DEVICE).groupBy { it.layout }
            val expected = census.getJSONObject(prefix)
            assertEquals(expected.keys().asSequence().toSet(), actual.keys)
            for (cabinet in expected.keys()) {
                val ids = expected.getJSONObject(cabinet)
                assertEquals("$prefix/$cabinet", ids.keys().asSequence().associateWith { ids.getInt(it) },
                    actual.getValue(cabinet).groupingBy { it.text }.eachCount())
                assertFunctionsFollowOwners(result, cabinet)
            }
            assertWorkbookOrder(result)
            // Retain the cloud's actual native-path associations for independent PDF comparison.
            // These are test evidence in the reports artifact, never hardcoded app results.
            val rows = JSONArray()
            result.records.filter { it.kind == RecordKind.LABEL && it.column in setOf(LabelColumn.DEVICE, LabelColumn.FUNCTION) }
                .forEach { rows.put(JSONObject().put("column", it.column.name).put("cabinet", it.layout)
                    .put("page", it.page).put("device", it.deviceId).put("text", it.text)
                    .put("installation", it.installationId).put("x", it.x).put("y", it.y)) }
            val notes = JSONArray()
            result.issues.forEach { notes.put(JSONObject().put("cabinet", it.layout).put("page", it.page).put("text", it.message)) }
            val directory = File("build/reports/extraction-regression").apply { mkdirs() }
            File(directory, "$prefix.json").writeText(JSONObject().put("records", rows).put("notes", notes).toString(2))
            if (prefix == "survey-layouts") {
                // Native cabinet artwork: these drives share a rail. The final occurrence
                // identifies the actual drive, after the same ID near its upstream breaker.
                for ((cabinet, rail) in mapOf(
                    "C09" to listOf("UM14", "UM145", "UM20/UM21", "UM22/UM23"),
                    "C22" to listOf("UM15", "UM18", "UM19", "UM16/UM17", "UM136"),
                )) {
                    val ids = labels(result, LabelColumn.DEVICE, cabinet).map { it.deviceId }
                    val positions = rail.map { ids.lastIndexOf(it) }
                    assertTrue("$cabinet drive rail $rail ${dump(result)}", positions.all { it >= 0 } &&
                        positions.zipWithNext().all { (left, right) -> left < right })
                }
                for ((cabinet, rail) in mapOf("C09" to listOf("UM14", "UM145"),
                    "C22" to listOf("UM15", "UM18", "UM19", "UM136"))) {
                    val ids = labels(result, LabelColumn.FUNCTION, cabinet).map { it.deviceId }
                    val positions = rail.map { ids.lastIndexOf(it) }
                    assertTrue("$cabinet function rail $rail ${dump(result)}", positions.all { it >= 0 } &&
                        positions.zipWithNext().all { (left, right) -> left < right })
                }
            }
        }
    }

    private val c01 = words(
        "1XT01 1QF01 1QF02 1QF03 1PV01 1KVS01 1SPD01 " +
            "1QS01 1FB01 1FB02 1FB03 " +
            "1QF04 1QF05 1QF06 1QF07 1QF08 " +
            "1TA01 1TA02 1TA03 1KM03 1KM04 1KM05 1KM06 1KM07 " +
            "1XT01 1XT01 1XT01 1XT01 1XT20 1XT30 1XT30 1XT30 1XT30 1XS01 1XS02 " +
            "1FU01 1FU02 1FU03 1FU04 1FU05 1FU06 1FU09 1FU10 1FU07 1FU08 1XT50 1XT51 1XT52 1XT55 1XT60 1SR01 1XT02",
    )
    private val c02 = words(
        "2XT01 2XT10 2FB01 2QF01 2QF02 2QF03 2QF04 2QF05 2QF06 2QF07 2QF08 2QF09 2KA03 " +
            "2G01 2G02 2G03 2G04 2G05 2G06 2G07 2G08 2G09 " +
            "2CPU01 SCR01 2XT55 2XT53 2XT56 2GW01 2GW02 2CP01 2SW01 " +
            "2IM01 2DI01 2DI02 2DI03 2DI04 2DI05 2DI06 2DO01 2PS01 2DO02 2DO03 2DO04 2DI01 2SR01 2SR02 2SR03 2SR04 2SR05 2SR06 2SR07 2SR08 2KA01 2KA02 " +
            "2QF10 2QF11 2QF12 2FU01 2FU02 2FU03 2FU10 2FU11 2FU12 2FU13 2FU04 2FU05 2FU06 2FU07 2FU08 2FU15 2FU09 2FU14 2FU16 2FU17 2FU18 2FU19 2XT61 2XT62 2XT80 2XT81 2XT87 " +
            "2XT57 2XT71 2XT51 2XT52 2XT54 2KA04 2XT88 2XT89 2XT90 2OC01 2OC02 2OC03 2OC04 2OC05 2OC06 2OC07 2OC09 2OC10 2OC11 2OC12 2OC13 2OC14 2OC15",
    )
    private val c03 = words(
        "3XT01 3XT02 3XT20 3XT40 3FB01 3FB02 3KM01 3KM02 3QF07 3QF08 3QF09 3QF10 3QF11 3QF12 3QF13 " +
            "3FB03 3FB06 3FB07 3FB08 3FB09 3QF02 3KA01 3KA02 3KA03 3KA04 3KA05 3FU01 3FU02 3FU03 3XT51 3XT52 3XT55",
    )

    @Test fun defaultProductionPathCorrectsBothFailedCabinetsAndPreservesTheAlreadyCorrectOne() =
        withFixture("layout-order") { document ->
            val result = extract(document, ExtractionMode.FULL)
            for ((cabinet, expected) in mapOf("C01" to c01, "C02" to c02, "C03" to c03)) {
                assertEquals(dump(result), expected, labels(result, LabelColumn.DEVICE, cabinet).map { it.text })
                assertFunctionsFollowOwners(result, cabinet)
            }
            // These were the first entries in the actual failed phone workbook. Preserve this
            // distinction so that a no-image text fallback can never make this regression pass.
            assertEquals("1XT01", labels(result, LabelColumn.DEVICE, "C01").first().text)
            assertEquals("3XT01", labels(result, LabelColumn.DEVICE, "C03").first().text)
            assertFalse(dump(result), result.issues.any { it.message.contains("安装行结构未能可靠识别") })
            assertEquals(dump(result), c01Functions, labels(result, LabelColumn.FUNCTION, "C01").map { it.deviceId to it.text })
            assertWorkbookOrder(result)
        }

    @Test fun actualPdfRotationDoesNotChangePhysicalOrderOrSeparateSignalOwnerOrder() {
        for (rotation in listOf(90, 180, 270)) withFixture("layout-order", rotation) { document ->
            val result = extract(document, ExtractionMode.FULL, RuleSettings(cabinetFilter = "C01 C03"))
            assertEquals("PDF /Rotate=$rotation ${dump(result)}", c01, labels(result, LabelColumn.DEVICE, "C01").map { it.text })
            assertEquals("PDF /Rotate=$rotation ${dump(result)}", c03, labels(result, LabelColumn.DEVICE, "C03").map { it.text })
            assertEquals("PDF /Rotate=$rotation ${dump(result)}", c01Functions,
                labels(result, LabelColumn.FUNCTION, "C01").map { it.deviceId to it.text })
            assertFunctionsFollowOwners(result, "C01")
            assertFunctionsFollowOwners(result, "C03")
        }
    }

    @Test fun b06DefaultPathKeepsAllSixRowsTheElevenModulesAndFifteenIndependentSignals() =
        withFixture("b06-layouts") { document ->
            val result = extract(document, ExtractionMode.FULL, RuleSettings(cabinetFilter = "C01"))
            val bands = JSONArray(resource("b06-c01-order.json"))
            val expected = (0 until bands.length()).flatMap { index ->
                val band = bands.getJSONArray(index)
                (0 until band.length()).map { band.getString(it) }
            }
            assertEquals(dump(result), expected, labels(result, LabelColumn.DEVICE, "C01").map { it.text })
            val functions = labels(result, LabelColumn.FUNCTION, "C01")
            val signals = linkedMapOf(
                "1KA01" to listOf("1：急停辅助输出", "2：安全光栅辅助输出", "3：安全门禁辅助输出", "4：三段式开关辅助输出",
                    "5：正常模式辅助输出", "6：维修模式辅助输出", "7：安全复位", "8：备用"),
                "1KA02" to listOf("1:冷水机启动", "2：除尘机启动"),
                "1KA03" to listOf("1：冷水机报警", "2：除尘机报警"),
                "1KA04" to listOf("1：NG缓存D调速电机1", "2：NG缓存D调速电机2", "3：NG缓存D调速电机3"),
            )
            signals.forEach { (owner, captions) ->
                assertEquals("$owner ${dump(result)}", captions, functions.filter { it.deviceId == owner }.map { it.text })
            }
            val modules = linkedMapOf("ECT01" to "耦合器", "AI101" to "模拟量输入", "AI102" to "模拟量输入",
                "AI103" to "模拟量输入", "AI104" to "模拟量输入", "AI105" to "模拟量输入", "AO101" to "模拟量输出",
                "DI101" to "数字量输入", "DI102" to "数字量输入", "DO101" to "数字量输出", "DO102" to "数字量输出")
            assertEquals(modules.toList(), functions.filter { it.deviceId in modules }.map { it.deviceId to it.text })
            assertFunctionsFollowOwners(result, "C01")
            assertWorkbookOrder(result)
        }

    @Test fun separatelyExtractedColumnsFreshInstancesAndSerializedExportsKeepTheSameOrder() =
        withFixture("layout-order") { document ->
            val full = extract(document, ExtractionMode.FULL)
            val aOnly = extract(document, ExtractionMode.DEVICE)
            val bOnly = extract(document, ExtractionMode.FUNCTION)
            assertEquals(signature(full, LabelColumn.DEVICE), signature(aOnly, LabelColumn.DEVICE))
            assertEquals(signature(full, LabelColumn.FUNCTION), signature(bOnly, LabelColumn.FUNCTION))
            val gson = com.google.gson.Gson()
            val saved = gson.toJson(PocketData(documents = listOf(document), results = listOf(full, aOnly, bOnly)))
            val restored = gson.fromJson(saved, PocketData::class.java).results
            assertEquals(3, restored.size)
            for ((original, reloaded) in listOf(full, aOnly, bOnly).zip(restored)) {
                assertEquals(original.records, reloaded.records)
                assertWorkbookOrder(reloaded)
            }
        }

    @Test fun wideTerminalAndOverlappingRelayCaptionsKeepTheirOwnersAcrossPdfRotationCacheAndExport() {
        for (rotation in listOf(0, 90, 180, 270)) withFixture("b06-layouts", rotation) { document ->
            val rules = RuleSettings(cabinetFilter = "C01")
            val full = extract(document, ExtractionMode.FULL, rules)
            val functions = labels(full, LabelColumn.FUNCTION, "C01")
            val expected = listOf("IO/ECT电源-N24", "IO电源-P24", "ECT电源-P24")
            assertEquals("PDF /Rotate=$rotation ${dump(full)}", expected,
                functions.filter { it.deviceId == "1XT60" }.map { it.text })
            val bank = functions.indexOfFirst { it.deviceId == "1XT60" }
            assertEquals(expected, functions.drop(bank).take(3).map { it.text })
            assertTrue(bank + 2 < functions.indexOfFirst { it.deviceId == "1QS01" })
            assertEquals(listOf("后段外罩照明启动"), functions.filter { it.deviceId == "1KA05" }.map { it.text })
            assertTrue(functions.indexOfLast { it.deviceId == "1KA04" } < functions.indexOfFirst { it.deviceId == "1KA05" })
            assertTrue(functions.indexOfFirst { it.deviceId == "1KA05" } < functions.indexOfFirst { it.deviceId == "1KA06" })
            assertEquals(listOf("接地铜排"), functions.filter { it.deviceId == "1XT02" }.map { it.text })
            assertEquals(listOf("数字量输出"), functions.filter { it.deviceId == "DO102" }.map { it.text })
            assertFunctionsFollowOwners(full, "C01")
            assertWorkbookOrder(full)
            val separate = extract(document, ExtractionMode.FUNCTION, rules)
            assertEquals(signature(full, LabelColumn.FUNCTION), signature(separate, LabelColumn.FUNCTION))
            assertWorkbookOrder(separate)
        }
    }

    @Test fun b26AllCabinetsKeepEveryPrintedFuseRatingAndTheUnmarkedGroundTerminal() {
        withFixture("b26-layouts") { document ->
            val result = extract(document, ExtractionMode.FULL)
            assertB26Fuses(result)
            assertB26GroundTerminal(result)
            assertEquals(listOf("焊接2A_Z轴（伺服电机+抱闸）/焊接2B_Z轴（伺服电机+抱闸）"),
                labels(result, LabelColumn.FUNCTION, "C05").filter { it.deviceId == "UM10/13" }.map { it.text })
            assertEquals(listOf("1 FFU电源-L1"), labels(result, LabelColumn.FUNCTION, "C02")
                .filter { it.deviceId == "2XT98" }.map { it.text })
            assertEquals(listOf("1-2 电柜照明电源-N24", "3-4 电柜照明电源-P24"),
                labels(result, LabelColumn.FUNCTION, "C02").filter { it.deviceId == "2XT51" }.map { it.text })
            assertEquals(listOf("1-2照明-N24"), labels(result, LabelColumn.FUNCTION, "C03")
                .filter { it.deviceId == "3XT51" }.map { it.text })
            val census = JSONObject(resource("b26-layout-census.json"))
            for (cabinet in (1..7).map { "C0$it" }) {
                assertFunctionsFollowOwners(result, cabinet)
                val ids = census.getJSONObject(cabinet)
                assertEquals("$cabinet actual layout occurrences", ids.keys().asSequence().associateWith { ids.getInt(it) },
                    labels(result, LabelColumn.DEVICE, cabinet).groupingBy { it.text }.eachCount())
            }
            assertWorkbookOrder(result)
            val rows = JSONArray()
            result.records.filter { it.kind == RecordKind.LABEL && it.column in setOf(LabelColumn.DEVICE, LabelColumn.FUNCTION) }
                .forEach { rows.put(JSONObject().put("column", it.column.name).put("cabinet", it.layout)
                    .put("page", it.page).put("device", it.deviceId).put("text", it.text)
                    .put("installation", it.installationId)) }
            val directory = File("build/reports/extraction-regression").apply { mkdirs() }
            File(directory, "b26-layouts.json").writeText(JSONObject().put("records", rows).toString(2))
            val separate = extract(document, ExtractionMode.FUNCTION)
            assertEquals(signature(result, LabelColumn.FUNCTION), signature(separate, LabelColumn.FUNCTION))
            assertWorkbookOrder(separate)
        }
    }

    @Test fun overlappingCurrentAndBareGroundTerminalSurviveRealPdfQuarterTurns() {
        for (rotation in listOf(90, 180, 270)) withFixture("b26-layouts", rotation) { document ->
            val result = extract(document, ExtractionMode.FULL, RuleSettings(cabinetFilter = "C01 C03"))
            assertB26Fuses(result, listOf("C01", "C03"))
            assertB26GroundTerminal(result)
            for (cabinet in listOf("C01", "C03")) assertFunctionsFollowOwners(result, cabinet)
            assertWorkbookOrder(result)
        }
    }

    @Test fun indiaMonochromeAndColoredCabinetsKeepTheirPhysicalDirectionRowsAndSeparateExports() {
        withFixture("india-layouts") { document ->
            val full = extract(document, ExtractionMode.FULL)
            val records = JSONArray()
            full.records.filter { it.kind == RecordKind.LABEL && it.column in setOf(LabelColumn.DEVICE, LabelColumn.FUNCTION) }
                .forEach { records.put(JSONObject().put("column", it.column.name).put("cabinet", it.layout)
                    .put("page", it.page).put("device", it.deviceId).put("text", it.text)
                    .put("installation", it.installationId).put("x", it.x).put("y", it.y)) }
            val notes = JSONArray()
            full.issues.forEach { notes.put(JSONObject().put("cabinet", it.layout).put("page", it.page).put("text", it.message)) }
            val directory = File("build/reports/extraction-regression").apply { mkdirs() }
            File(directory, "india-layouts.json").writeText(JSONObject().put("records", records).put("notes", notes).toString(2))
            assertIndiaLayouts(full, 0)
            assertWorkbookOrder(full)
            for (mode in listOf(ExtractionMode.DEVICE, ExtractionMode.FUNCTION)) {
                val separate = extract(document, mode)
                val column = if (mode == ExtractionMode.DEVICE) LabelColumn.DEVICE else LabelColumn.FUNCTION
                assertEquals("$mode with the saved geometry plan", signature(full, column), signature(separate, column))
                assertWorkbookOrder(separate)
            }
        }
    }

    @Test fun indiaNativeImagePlacementAndPageQuarterTurnsPreserveTheSameInstallationRows() {
        for (rotation in listOf(90, 180, 270)) withFixture("india-layouts", rotation) { document ->
            val result = extract(document, ExtractionMode.FULL)
            assertIndiaLayouts(result, rotation)
            assertWorkbookOrder(result)
        }
    }

    private fun assertIndiaLayouts(result: ExtractionResult, displayedRotation: Int) {
        val expected = JSONObject(resource("india-layout-order.json"))
        for (cabinet in expected.keys()) {
            val values = expected.getJSONObject(cabinet)
            val devices = labels(result, LabelColumn.DEVICE, cabinet).map { it.text }
            val census = values.getJSONObject("census")
            val message = "$cabinet /Rotate=$displayedRotation ${dump(result)}"
            assertEquals(message, census.keys().asSequence().associateWith { census.getInt(it) }, devices.groupingBy { it }.eachCount())
            val turn = (values.getInt("clockwise") - displayedRotation + 360) % 360
            val expectedNote = if (turn == 0) "布局方向：保持原方向" else "布局方向：顺时针旋转 ${turn}°"
            assertTrue(message, result.issues.any { it.layout == cabinet && it.message.startsWith(expectedNote) })
            assertFalse(message, result.issues.any { it.layout == cabinet && it.message.contains("安装行结构未能可靠识别") })
            fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
            values.optJSONArray("order")?.let { assertEquals(message, strings(it), devices) }
            values.optJSONArray("first")?.let { assertEquals(message, strings(it), devices.take(it.length())) }
            values.optJSONArray("last")?.let { assertEquals(message, strings(it), devices.takeLast(it.length())) }
            values.optJSONArray("before")?.let { constraints ->
                for (index in 0 until constraints.length()) {
                    val constraint = constraints.getJSONObject(index)
                    val earlierNames = strings(constraint.getJSONArray("earlier"))
                    val laterNames = strings(constraint.getJSONArray("later"))
                    val earlier = devices.indices.filter { devices[it] in earlierNames }
                    val later = devices.indices.filter { devices[it] in laterNames }
                    assertTrue("Physical compartment precedence $earlierNames before $laterNames: $message",
                        earlierNames.all { it in devices } && laterNames.all { it in devices } &&
                            earlier.maxOrNull()!! < later.minOrNull()!!)
                }
            }
            val rows = values.getJSONArray("rows")
            var previousEnd = 0
            for (index in 0 until rows.length()) {
                val row = strings(rows.getJSONArray(index))
                val start = (previousEnd..(devices.size - row.size)).firstOrNull { position ->
                    devices.subList(position, position + row.size) == row
                }
                assertNotNull("Row ${index + 1}: $row $message", start)
                previousEnd = requireNotNull(start) + row.size
            }
            assertFunctionsFollowOwners(result, cabinet)
        }
    }

    private fun assertB26GroundTerminal(result: ExtractionResult) {
        val devices = labels(result, LabelColumn.DEVICE, "C01")
        // Original B26 p113 clockwise90: four terminal bodies lie left of the ground bar.
        // The earlier B06 drawing has a different top row; do not impose its order here.
        assertEquals("Native top-row order", listOf("1XT04", "1XT04", "1XT04", "1XT04", "1XT01"), devices.take(5).map { it.text })
        assertEquals(1, devices.count { it.deviceId == "1XT01" })
        assertEquals(listOf("接地端子"), labels(result, LabelColumn.FUNCTION, "C01")
            .filter { it.deviceId == "1XT01" }.map { it.text })
        assertFalse(dump(result), result.issues.any { it.layout == "C01" && it.message.contains("安装行结构未能可靠识别") })
    }

    private fun assertB26Fuses(result: ExtractionResult, cabinets: List<String> = (1..7).map { "C0$it" }) {
        // Transcribed from original PDF native ID/current/caption triplets, not engine output.
        val expected = JSONObject(resource("b26-fuse-expectations.json"))
        for (cabinet in cabinets) {
            val values = expected.getJSONObject(cabinet)
            val actual = labels(result, LabelColumn.FUNCTION, cabinet)
                .filter { EplanParsing.deviceCategory(it.deviceId) == "FU" }
            assertEquals("$cabinet ${dump(result)}", values.length(), actual.size)
            assertEquals("$cabinet ${dump(result)}", values.keys().asSequence().associateWith { values.getString(it) },
                actual.associate { it.deviceId to it.text })
            assertEquals("$cabinet fuse physical order", values.keys().asSequence().toList().sorted(), actual.map { it.deviceId })
        }
    }

    private val c01Functions = listOf(
        "1XT01" to "铜排", "1QF01" to "浪涌保护器", "1QF02" to "相序保护器", "1QF03" to "电能表",
        "1PV01" to "电能表", "1KVS01" to "相序保护", "1SPD01" to "浪涌保护器",
        "1QS01" to "塑壳断路器", "1FB01" to "二级电源", "1FB02" to "调试插座", "1FB03" to "平板电脑",
        "1QF04" to "1KM03", "1QF05" to "1KM04", "1QF06" to "1KM05", "1QF07" to "1KM06", "1QF08" to "1KM07",
        "1TA01" to "L1电流互感器", "1TA02" to "L2电流互感器", "1TA03" to "L3电流互感器",
        "1KM03" to "A机架控制电源（C07/C22）", "1KM04" to "A机架控制电源+B机架控制电源",
        "1KM05" to "D机架控制电源（C14）", "1KM06" to "D机架控制电源（C17）", "1KM07" to "D机架控制电源+C机架控制电源",
        "1XT01" to "【一】电源端子-N1", "1XT01" to "【一】电源端子-L1", "1XT01" to "【一】电源端子-L2", "1XT01" to "【一】电源端子-L3",
        "1XT20" to "【二】电源端子-N1", "1XT20" to "【二】电源端子-L1", "1XT20" to "【二】电源端子-L2", "1XT20" to "【二】电源端子-L3",
        "1XT30" to "【三】电源端子-N1", "1XT30" to "【三】电源端子-L1", "1XT30" to "【三】电源端子-L2", "1XT30" to "【三】电源端子-L3",
        "1XS01" to "三孔插座", "1XS02" to "二孔插座",
        "1FU01" to "(3A)接触器", "1FU02" to "(2A)柜内照明", "1FU03" to "(2A)风扇电源1", "1FU04" to "(2A)风扇电源2",
        "1FU05" to "(2A)风扇电源3", "1FU06" to "(2A)风扇电源4", "1FU09" to "(2A)风扇电源5", "1FU10" to "(2A)风扇电源6",
        "1FU07" to "(6A)安全总电源", "1FU08" to "(2A)IO电源",
        "1XT50" to "接触器电源-N24", "1XT50" to "接触器电源-P24", "1XT51" to "电柜照明-N24", "1XT51" to "电柜照明-P24",
        "1XT52" to "风扇电源-N24", "1XT52" to "风扇电源-P24", "1XT55" to "接触器控制", "1XT55" to "接触器控制",
        "1XT60" to "接触器控制", "1XT60" to "接触器控制", "1SR01" to "接触器控制", "1XT02" to "接地端子",
    )

    private fun assertFunctionsFollowOwners(result: ExtractionResult, cabinet: String) {
        val devices = labels(result, LabelColumn.DEVICE, cabinet)
        val owners = devices.withIndex().associate { it.value.installationId to it.index }
        val functions = labels(result, LabelColumn.FUNCTION, cabinet)
        // A layout consisting only of uncaptioned resistors legitimately has no B labels.
        assertTrue(dump(result), functions.all { it.installationId in owners })
        val order = functions.map { owners.getValue(it.installationId) }
        assertEquals("B follows each physical A occurrence ${dump(result)}", order.sorted(), order)
    }

    /** Read the resulting OOXML cells, so a later export-layer reorder cannot go unnoticed. */
    private fun assertWorkbookOrder(result: ExtractionResult) {
        val bytes = ByteArrayOutputStream().also { XlsxExporter.write(result, it) }.toByteArray()
        val sheet = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/worksheets/sheet1.xml") found = zip.readBytes()
            }
            requireNotNull(found)
        }
        val xml = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(ByteArrayInputStream(sheet))
        val cells = xml.getElementsByTagName("c")
        for (column in listOf(LabelColumn.DEVICE, LabelColumn.FUNCTION)) {
            val index = result.mode.columns.indexOf(column)
            if (index < 0) continue
            val letter = ('A'.code + index).toChar().toString()
            val exported = (0 until cells.length).map { cells.item(it) as Element }
                .filter { it.getAttribute("r").matches(Regex("$letter[0-9]+")) }
                .map { it.textContent }.drop(1)
            assertEquals("Exported $column order", XlsxExporter.exportRecords(result.records, column).map { it.text }, exported)
        }
    }

    private fun extract(document: ImportedDocument, mode: ExtractionMode, rules: RuleSettings = RuleSettings()): ExtractionResult =
        OfflinePdfEngine(cacheDirectory).extract(document, mode, rules, {}, { false }).also { assertTrue(dump(it), it.completed) }

    private fun signature(result: ExtractionResult, column: LabelColumn) = labels(result, column)
        .map { listOf(it.layout, it.installationId, it.deviceId, it.text) }
    private fun labels(result: ExtractionResult, column: LabelColumn, cabinet: String? = null) = result.records
        .filter { it.column == column && it.kind == RecordKind.LABEL && (cabinet == null || it.layout == cabinet) }
    private fun dump(result: ExtractionResult) = result.records.filter { it.kind == RecordKind.LABEL && it.column in setOf(LabelColumn.DEVICE, LabelColumn.FUNCTION) }
        .joinToString("\n", "\n") { "${it.column}/${it.layout}: ${it.deviceId} -> ${it.text}" } +
        result.issues.joinToString("\n", "\nissues:") { "${it.layout}/${it.page}: ${it.message}" }
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")).bufferedReader().use { it.readText() }
    private fun words(value: String) = value.split(' ')

    private fun withFixture(prefix: String, rotation: Int = 0, block: (ImportedDocument) -> Unit) {

        val id = UUID.randomUUID().toString()
        val file = File(cacheDirectory, "embedded-$id.pdf")
        val original = File(cacheDirectory, "embedded-source-$id.pdf")
        try {
            requireNotNull(javaClass.getResourceAsStream("/fixtures/$prefix-native.pdf")).use { input ->
                original.outputStream().use { input.copyTo(it) }
            }
            if (rotation == 0) original.copyTo(file) else PDDocument.load(original).use { pdf ->
                pdf.pages.forEach { it.rotation = rotation }
                pdf.save(file)
            }
            block(ImportedDocument(id, "$prefix.pdf", file.absolutePath, file.length(), 0L))
        } finally { file.delete(); original.delete() }
    }
}
