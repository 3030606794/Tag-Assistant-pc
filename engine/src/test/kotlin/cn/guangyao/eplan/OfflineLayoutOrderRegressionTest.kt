package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Original layout pages 164/190/252 with original image matrices, fonts, colors, text geometry
 * and hit rectangles. Images retain rails, ducts, device bodies and bottom openings. Logos,
 * stamps and title blocks are removed; links stay on their own fixture page and contain no JS.
 *
 * The PNGs are bounded 1200px renders of these exact fixture pages in displayed PDF coordinates.
 * Only the raster bridge is injected: PDF decoding, geometry, order persistence and
 * extraction all use the production engine. Expected rows were independently read from the
 * original drawing with C01/C02 turned clockwise and C03 left as drawn. No local app execution.
 */
class OfflineLayoutOrderRegressionTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    private val c01Rows = listOf(
        words("1XT01 1QF01 1QF02 1QF03 1PV01 1KVS01 1SPD01"),
        words("1QS01 1FB01 1FB02 1FB03"),
        words("1QF04 1QF05 1QF06 1QF07 1QF08"),
        words("1TA01 1TA02 1TA03 1KM03 1KM04 1KM05 1KM06 1KM07"),
        words("1XT01 1XT01 1XT01 1XT01 1XT20 1XT30 1XT30 1XT30 1XT30 1XS01 1XS02"),
        words("1FU01 1FU02 1FU03 1FU04 1FU05 1FU06 1FU09 1FU10 1FU07 1FU08 1XT50 1XT51 1XT52 1XT55 1XT60 1SR01 1XT02"),
    )
    private val c02Rows = listOf(
        words("2XT01 2XT10 2FB01 2QF01 2QF02 2QF03 2QF04 2QF05 2QF06 2QF07 2QF08 2QF09 2KA03"),
        words("2G01 2G02 2G03 2G04 2G05 2G06 2G07 2G08 2G09"),
        words("2CPU01 SCR01 2XT55 2XT53 2XT56 2GW01 2GW02 2CP01 2SW01"),
        // DI01 is printed at two separate installed modules; preserve the source duplication.
        words("2IM01 2DI01 2DI02 2DI03 2DI04 2DI05 2DI06 2DO01 2PS01 2DO02 2DO03 2DO04 2DI01 2SR01 2SR02 2SR03 2SR04 2SR05 2SR06 2SR07 2SR08 2KA01 2KA02"),
        words("2QF10 2QF11 2QF12 2FU01 2FU02 2FU03 2FU10 2FU11 2FU12 2FU13 2FU04 2FU05 2FU06 2FU07 2FU08 2FU15 2FU09 2FU14 2FU16 2FU17 2FU18 2FU19 2XT61 2XT62 2XT80 2XT81 2XT87"),
        words("2XT57 2XT71 2XT51 2XT52 2XT54 2KA04 2XT88 2XT89 2XT90 2OC01 2OC02 2OC03 2OC04 2OC05 2OC06 2OC07 2OC09 2OC10 2OC11 2OC12 2OC13 2OC14 2OC15"),
    )
    private val c03Rows = listOf(
        words("3XT01 3XT02 3XT20 3XT40 3FB01 3FB02 3KM01 3KM02 3QF07 3QF08 3QF09 3QF10 3QF11 3QF12 3QF13"),
        words("3FB03 3FB06 3FB07 3FB08 3FB09 3QF02 3KA01 3KA02 3KA03 3KA04 3KA05 3FU01 3FU02 3FU03 3XT51 3XT52 3XT55"),
    )

    @Test fun c01AllFiftyTwoDevicesFollowSixActualInstallationBands() = withFixture { document ->
        val result = extract(document, NativeRasters(), ExtractionMode.FULL)
        assertEquals(dump(result), c01Rows.flatten(), labels(result, LabelColumn.DEVICE, "C01").map { it.text })
        val xt01 = labels(result, LabelColumn.DEVICE, "C01").filter { it.deviceId == "1XT01" }
        assertEquals(dump(result), 5, xt01.size)
        assertTrue("First XT01 is the separate copper bar, not a lower power block. ${dump(result)}", xt01.first().x < 400f)
        assertTrue(dump(result), xt01.drop(1).all { it.x > 700f })
        assertFunctionsFollowOwners(result, "C01")
    }

    @Test fun c02AllOneHundredFourDevicesKeepSixBandsIncludingTheSidewaysPowerSupplies() = withFixture { document ->
        val result = extract(document, NativeRasters(), ExtractionMode.DEVICE)
        assertEquals(dump(result), c02Rows.flatten(), labels(result, LabelColumn.DEVICE, "C02").map { it.text })
        assertEquals(dump(result), 104, labels(result, LabelColumn.DEVICE, "C02").size)
    }

    @Test fun c03AllThirtyTwoDevicesKeepTwoUprightBandsDespiteVerticalLegends() = withFixture { document ->
        val result = extract(document, NativeRasters(), ExtractionMode.FULL)
        assertEquals(dump(result), c03Rows.flatten(), labels(result, LabelColumn.DEVICE, "C03").map { it.text })
        // FB01/02 labels sit higher than their neighbors, but both bodies are on the FIRST rail.
        assertEquals(dump(result), listOf("3FB01", "3FB02"),
            labels(result, LabelColumn.DEVICE, "C03").drop(4).take(2).map { it.text })
        assertFunctionsFollowOwners(result, "C03")
    }

    @Test fun c01AllSixtyFunctionsFollowTheirDevicesAndTurnTogetherWithinEachTerminalBank() = withFixture { document ->
        val result = extract(document, NativeRasters(), ExtractionMode.FULL)
        val expected = listOf(
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
            "1XT50" to "接触器电源-N24", "1XT50" to "接触器电源-P24",
            "1XT51" to "电柜照明-N24", "1XT51" to "电柜照明-P24",
            "1XT52" to "风扇电源-N24", "1XT52" to "风扇电源-P24",
            "1XT55" to "接触器控制", "1XT55" to "接触器控制",
            "1XT60" to "接触器控制", "1XT60" to "接触器控制",
            "1SR01" to "接触器控制", "1XT02" to "接地端子",
        )
        assertEquals(dump(result), expected, labels(result, LabelColumn.FUNCTION, "C01").map { it.deviceId to it.text })
        assertFunctionsFollowOwners(result, "C01")
    }

    @Test fun individualColumnsFullAndFreshEngineRerunsUseTheSamePersistedOrderWithoutRerendering() = withFixture { document ->
        val rasters = NativeRasters()
        val aOnly = extract(document, rasters, ExtractionMode.DEVICE)
        assertEquals("All three genuine raster layouts must have been analyzed", listOf(1, 2, 3), rasters.pages)
        val bOnly = extract(document, rasters, ExtractionMode.FUNCTION)
        val full = extract(document, rasters, ExtractionMode.FULL)
        val cached = extract(document, rasters, ExtractionMode.FULL)
        assertEquals("Confident installation plans must survive fresh engine instances without another raster read",
            listOf(1, 2, 3), rasters.pages)
        assertTrue(dump(aOnly), aOnly.records.all { it.column == LabelColumn.DEVICE })
        assertTrue(dump(bOnly), bOnly.records.all { it.column == LabelColumn.FUNCTION })
        assertEquals(dump(full), abSignatures(aOnly, LabelColumn.DEVICE), abSignatures(full, LabelColumn.DEVICE))
        assertEquals(dump(full), abSignatures(bOnly, LabelColumn.FUNCTION), abSignatures(full, LabelColumn.FUNCTION))
        assertEquals(dump(cached), abSignatures(full, LabelColumn.DEVICE), abSignatures(cached, LabelColumn.DEVICE))
        assertEquals(dump(cached), abSignatures(full, LabelColumn.FUNCTION), abSignatures(cached, LabelColumn.FUNCTION))
        for (layout in listOf("C01", "C02", "C03")) assertFunctionsFollowOwners(full, layout)
    }

    @Test fun automaticInstallationOrderDoesNotChangeOtherColumnsOrInventAdditionalLabels() = withFixture { document ->
        // First run intentionally has no image evidence, exercising the unchanged legacy order.
        // An unavailable raster must not cache a false confident plan and block the real next run.
        val legacy = extract(document, LayoutRasterSource { _, _, _ -> null }, ExtractionMode.FULL)
        val ordered = extract(document, NativeRasters(), ExtractionMode.FULL)
        val unaffected = setOf(LabelColumn.YELLOW, LabelColumn.CN1, LabelColumn.FIXTURE, LabelColumn.QUANTITY)
        assertEquals(dump(ordered), otherSignatures(legacy, unaffected), otherSignatures(ordered, unaffected))
        assertFalse("Fixture contains actual fixed-base labels, so the isolation assertion is not empty",
            legacy.records.none { it.column == LabelColumn.FIXTURE && it.kind == RecordKind.LABEL })
        assertFalse("Only order is changed; the C01 device order should actually differ from its legacy order",
            labels(legacy, LabelColumn.DEVICE, "C01").map { it.text } == labels(ordered, LabelColumn.DEVICE, "C01").map { it.text })
        for (column in listOf(LabelColumn.DEVICE, LabelColumn.FUNCTION)) {
            fun counts(result: ExtractionResult) = result.records.filter { it.column == column && it.kind == RecordKind.LABEL }
                .groupingBy { listOf(it.layout, it.deviceId, it.text, it.x.toString(), it.y.toString()) }.eachCount()
            assertEquals("Ordering cannot lose, add or rebind source labels. ${dump(ordered)}", counts(legacy), counts(ordered))
        }
    }

    private fun extract(document: ImportedDocument, rasters: LayoutRasterSource, mode: ExtractionMode): ExtractionResult =
        OfflinePdfEngine(cacheDirectory, rasters).extract(document, mode, RuleSettings(), {}, { false })

    private fun labels(result: ExtractionResult, column: LabelColumn, layout: String): List<LabelRecord> =
        result.records.filter { it.column == column && it.kind == RecordKind.LABEL && it.layout == layout }

    private fun assertFunctionsFollowOwners(result: ExtractionResult, layout: String) {
        val devices = labels(result, LabelColumn.DEVICE, layout)
        val index = devices.withIndex().associate { it.value.installationId to it.index }
        val functions = labels(result, LabelColumn.FUNCTION, layout)
        assertTrue(dump(result), functions.isNotEmpty())
        assertTrue(dump(result), functions.all { it.installationId in index })
        val sequence = functions.map { index.getValue(it.installationId) }
        assertEquals("B must follow physical A owners, including devices with several captions. ${dump(result)}",
            sequence.sorted(), sequence)
    }

    private fun abSignatures(result: ExtractionResult, column: LabelColumn) = result.records
        .filter { it.column == column && it.kind == RecordKind.LABEL }
        .map { listOf(it.layout, it.installationId, it.deviceId, it.text, it.page.toString()) }

    private fun otherSignatures(result: ExtractionResult, columns: Set<LabelColumn>) = result.records
        .filter { it.column in columns }
        .map { listOf(it.column.name, it.kind.name, it.layout, it.text, it.page.toString(), it.x.toString(), it.y.toString()) }

    private fun dump(result: ExtractionResult): String = buildString {
        append("\nmode=${result.mode}; completed=${result.completed}\n")
        for (layout in listOf("C01", "C02", "C03")) {
            append("$layout A: ${labels(result, LabelColumn.DEVICE, layout).joinToString { it.text }}\n")
            labels(result, LabelColumn.FUNCTION, layout).forEach {
                append("$layout B: ${it.deviceId} -> ${it.text}; source=${it.page}:${it.x},${it.y}; owner=${it.installationId}\n")
            }
        }
        result.issues.forEach { append("issue: ${it.layout} page=${it.page}, ${it.message}\n") }
    }

    private class NativeRasters : LayoutRasterSource {
        val pages = mutableListOf<Int>()
        override fun read(document: ImportedDocument, page: Int, checkCancelled: () -> Unit): AbInstallationOrder.Raster {
            checkCancelled()
            pages += page
            val input = requireNotNull(javaClass.getResourceAsStream("/fixtures/layout-order-p$page.png"))
            val image = input.use { requireNotNull(ImageIO.read(it)) }
            assertEquals(1200, image.width)
            assertEquals(852, image.height)
            return AbInstallationOrder.Raster(image.width, image.height,
                image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        }
    }

    private fun words(value: String) = value.split(' ')

    private fun withFixture(block: (ImportedDocument) -> Unit) {
        val id = UUID.randomUUID().toString()
        val file = File(cacheDirectory, "layout-order-$id.pdf")
        requireNotNull(javaClass.getResourceAsStream("/fixtures/layout-order-native.pdf")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try { block(ImportedDocument(id, "Native order.pdf", file.absolutePath, file.length(), 0L)) }
        finally { file.delete() }
    }
}
