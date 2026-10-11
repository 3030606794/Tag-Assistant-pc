package cn.guangyao.eplan

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native EPLAN regressions, independently checked against the actual installation drawing.
 *
 * The small fixture contains only the original text operators from layout page 164 and its
 * conflicting function table (page 166): original font, glyph coordinates, colors, 45-degree
 * transforms and local link hit rectangles. Drawing images, stamps and title blocks are absent.
 * Link targets point back to the layout and contain no JavaScript. Character coordinates, color,
 * size and rotation were compared exactly with the source after creating the fixture.
 *
 * Expected values come from the drawing, not from the extractor's current output. In particular,
 * the smaller slanted -1KM07 beside QF08 describes that breaker; it is not a second installation.
 * Run only in cloud CI, using the same Android PDF decoder as the release application.
 */
class OfflineLayoutRegressionTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    @Test fun nativeFixtureRetainsItsConflictingSourcesAndRealLinkRectangles() = withFixture { document ->

        PDDocument.load(File(document.path)).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val glyphs = mutableListOf<TextPosition>()
            object : PDFTextStripper() {
                init { startPage = 1; endPage = 1 }
                override fun processTextPosition(text: TextPosition) { glyphs += text }
            }.getText(pdf)
            val tableText = PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(pdf)
            // PDFTextStripper's default text order inserts line breaks inside slanted strings.
            // Independently inspect the two original baselines, without the production joiner.
            assertEquals("Main installed KM07 at its original horizontal anchor", "-1KM07",
                nativeBaseline(glyphs, 610.276f, 199.952f, 0, 28f))
            assertEquals("QF08's separate slanted reference at its original anchor", "-1KM07",
                nativeBaseline(glyphs, 548.409f, 200.947f, -45, 17f))
            assertEquals("二孔插座", nativeBaseline(glyphs, 859.035f, 182.409f, -45, 22f))
            assertEquals("D机架控制电源+C机架控制电源",
                nativeBaseline(glyphs, 713.721f, 203.400f, -45, 76f))
            assertTrue(tableText.contains("UPS排插"))
            assertTrue(tableText.contains("正泰"))
            assertTrue("Real device-property hit rectangles must be exercised", pdf.getPage(0).annotations.size >= 200)
        }
    }

    @Test fun layoutSocketsAndContactorKeepTheirExactOwnCaptions() = withFixture { document ->
        val result = extract(document, ExtractionMode.FULL)
        assertTrue(dump(result), result.completed)
        val expected = linkedMapOf(
            "1XS02" to "二孔插座",
            "1XS01" to "三孔插座",
            "1KM07" to "D机架控制电源+C机架控制电源",
            "1KM06" to "D机架控制电源（C17）",
            "1KM05" to "D机架控制电源（C14）",
            "1KM04" to "A机架控制电源+B机架控制电源",
            "1KM03" to "A机架控制电源（C07/C22）",
        )
        expected.forEach { (id, caption) ->
            val devices = labels(result, LabelColumn.DEVICE).filter { it.deviceId == id }
            val functions = labels(result, LabelColumn.FUNCTION).filter { it.deviceId == id }
            assertEquals("One actual installed $id is drawn. ${dump(result)}", 1, devices.size)
            assertEquals("Layout caption for $id. ${dump(result)}", listOf(caption), functions.map { it.text })
            assertEquals(dump(result), devices.single().installationId, functions.single().installationId)
            assertEquals(dump(result), "installation-label", functions.single().evidence)
            assertEquals(dump(result), 1, functions.single().page)
        }
        assertFalse(dump(result), labels(result, LabelColumn.FUNCTION).any {
            it.text.contains("UPS排插") || it.text.contains("正泰") || it.text.contains("NC1-")
        })
    }

    @Test fun allTenDenseFuseRowsUseOnlyTheirOwnPrintedRating() = withFixture { document ->
        val result = extract(document, ExtractionMode.FUNCTION)
        val expected = linkedMapOf(
            "1FU01" to "(3A)接触器",
            "1FU02" to "(2A)柜内照明",
            "1FU03" to "(2A)风扇电源1",
            "1FU04" to "(2A)风扇电源2",
            "1FU05" to "(2A)风扇电源3",
            "1FU06" to "(2A)风扇电源4",
            "1FU07" to "(6A)安全总电源",
            "1FU08" to "(2A)IO电源",
            "1FU09" to "(2A)风扇电源5",
            "1FU10" to "(2A)风扇电源6",
        )
        expected.forEach { (id, caption) ->
            assertEquals("Fuse's own same-baseline rating for $id. ${dump(result)}", listOf(caption),
                labels(result, LabelColumn.FUNCTION).filter { it.deviceId == id }.map { it.text })
        }
        assertFalse("Nearby non-fuses must not inherit a fuse rating. ${dump(result)}",
            labels(result, LabelColumn.FUNCTION).any {
                EplanParsing.deviceCategory(it.deviceId) != "FU" && Regex("^\\([0-9.]+A\\)").containsMatchIn(it.text)
            })
    }

    @Test fun referenceCaptionDoesNotBecomeAnInstalledDeviceAndWideDeviceKeepsItsOwnLayoutCaption() = withFixture { document ->
        val result = extract(document, ExtractionMode.FULL)
        val devices = labels(result, LabelColumn.DEVICE)
        val functions = labels(result, LabelColumn.FUNCTION)
        assertEquals(dump(result), 1, devices.count { it.deviceId == "1KM07" })
        assertTrue("Real KM07 is the contactor at x610, not the QF08 caption at x548. ${dump(result)}",
            devices.single { it.deviceId == "1KM07" }.x > 600f)
        assertEquals("A reference may be a layout caption, never an extra device. ${dump(result)}",
            listOf("1KM07"), functions.filter { it.deviceId == "1QF08" }.map { it.text })
        assertEquals(dump(result), 1, devices.count { it.deviceId == "1QS01" })
        // The wide QS01's own caption is at the body's far right, beside TA03. Its presence is
        // established on the original drawing; it must neither be omitted nor assigned to TA03.
        assertEquals("QS01's actual layout caption at x672. ${dump(result)}",
            listOf("塑壳断路器"), functions.filter { it.deviceId == "1QS01" }.map { it.text })
    }

    @Test fun fourRealTerminalPositionsSurviveWithoutGlobalIdentifierDeduplication() = withFixture { document ->
        val result = extract(document, ExtractionMode.FULL)
        val devices = labels(result, LabelColumn.DEVICE).filter { it.deviceId == "1XT30" }
        val functions = labels(result, LabelColumn.FUNCTION).filter { it.deviceId == "1XT30" }
        assertEquals(dump(result), 4, devices.size)
        assertEquals(dump(result), 4, devices.map { it.installationId }.distinct().size)
        assertEquals(dump(result), setOf("【三】电源端子-L3", "【三】电源端子-L2", "【三】电源端子-L1", "【三】电源端子-N1"),
            functions.map { it.text }.toSet())
        assertEquals(dump(result), devices.map { it.installationId }.toSet(), functions.map { it.installationId }.toSet())
    }

    @Test fun completeLayoutContainsFiftyTwoPhysicalLabelsNotFiftySevenMentions() = withFixture { document ->
        val result = extract(document, ExtractionMode.DEVICE)
        // The original layout has five XT01 positions (four power blocks and one copper bar),
        // four XT30 positions, and one of every other listed device. The five small KM references
        // belong to QF04-QF08 property fields and add zero physical installations.
        val expected = ("1FB01 1FB02 1FB03 " +
            "1FU01 1FU02 1FU03 1FU04 1FU05 1FU06 1FU07 1FU08 1FU09 1FU10 " +
            "1KM03 1KM04 1KM05 1KM06 1KM07 1KVS01 1PV01 " +
            "1QF01 1QF02 1QF03 1QF04 1QF05 1QF06 1QF07 1QF08 1QS01 " +
            "1SPD01 1SR01 1TA01 1TA02 1TA03 1XS01 1XS02 " +
            "1XT01 1XT02 1XT20 1XT30 1XT50 1XT51 1XT52 1XT55 1XT60")
            .split(' ').associateWith { 1 }.toMutableMap().apply {
                this["1XT01"] = 5
                this["1XT30"] = 4
            }
        val devices = labels(result, LabelColumn.DEVICE)
        assertEquals(dump(result), 52, devices.size)
        assertEquals(dump(result), expected, devices.groupingBy { it.deviceId }.eachCount())
    }

    @Test fun everyPrintedTerminalSubgroupRetainsItsOwnFunctionIncludingRepeatedCaptions() = withFixture { document ->
        val result = extract(document, ExtractionMode.FULL)
        // Visually verified on the layout: these labels belong to separately printed terminal
        // subgroups, including identical "接触器控制" captions at distinct positions.
        val expected = linkedMapOf(
            "1XT01" to listOf("【一】电源端子-L3", "【一】电源端子-L2", "【一】电源端子-L1", "【一】电源端子-N1", "铜排"),
            "1XT20" to listOf("【二】电源端子-L3", "【二】电源端子-L2", "【二】电源端子-L1", "【二】电源端子-N1"),
            "1XT50" to listOf("接触器电源-P24", "接触器电源-N24"),
            "1XT51" to listOf("电柜照明-P24", "电柜照明-N24"),
            "1XT52" to listOf("风扇电源-P24", "风扇电源-N24"),
            "1XT55" to listOf("接触器控制", "接触器控制"),
            "1XT60" to listOf("接触器控制", "接触器控制"),
            "1SR01" to listOf("接触器控制"),
        )
        val functions = labels(result, LabelColumn.FUNCTION)
        expected.forEach { (id, captions) ->
            assertEquals("Every printed subgroup for $id, including multiplicity. ${dump(result)}",
                captions.groupingBy { it }.eachCount(),
                functions.filter { it.deviceId == id }.groupingBy { it.text }.eachCount())
        }
        assertEquals("52 installed IDs have 60 explicitly printed layout captions. ${dump(result)}", 60, functions.size)
    }

    @Test fun aOnlyBOnlyFullAndCachedRerunHaveTheSamePhysicalPairs() = withFixture { document ->
        val aOnly = extract(document, ExtractionMode.DEVICE)
        val bOnly = extract(document, ExtractionMode.FUNCTION)
        val full = extract(document, ExtractionMode.FULL)
        val cachedB = extract(document, ExtractionMode.FUNCTION)
        assertTrue(dump(aOnly), aOnly.records.all { it.column == LabelColumn.DEVICE })
        assertTrue(dump(bOnly), bOnly.records.all { it.column == LabelColumn.FUNCTION })
        fun signatures(result: ExtractionResult, column: LabelColumn) = labels(result, column).map {
            Triple(it.installationId, it.deviceId, it.text)
        }
        assertEquals(dump(full), signatures(aOnly, LabelColumn.DEVICE), signatures(full, LabelColumn.DEVICE))
        assertEquals(dump(full), signatures(bOnly, LabelColumn.FUNCTION), signatures(full, LabelColumn.FUNCTION))
        assertEquals(dump(cachedB), signatures(bOnly, LabelColumn.FUNCTION), signatures(cachedB, LabelColumn.FUNCTION))
        val physicalIds = labels(aOnly, LabelColumn.DEVICE).map { it.installationId }.toSet()
        assertTrue(dump(full), labels(full, LabelColumn.FUNCTION).all { it.installationId in physicalIds && it.page == 1 })
        assertEquals(dump(full), 1, labels(full, LabelColumn.DEVICE).count { it.deviceId == "1KM07" })
    }

    private fun extract(document: ImportedDocument, mode: ExtractionMode): ExtractionResult =
        OfflinePdfEngine(cacheDirectory).extract(document, mode, RuleSettings(), {}, { false })

    private fun labels(result: ExtractionResult, column: LabelColumn): List<LabelRecord> =
        result.records.filter { it.column == column && it.kind == RecordKind.LABEL }

    /** Read an independently known native baseline; this intentionally does not use EplanParsing. */
    private fun nativeBaseline(glyphs: List<TextPosition>, x: Float, y: Float, degrees: Int, length: Float): String {
        val angle = Math.toRadians(degrees.toDouble())
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        assertTrue("Missing native glyph origin ($x,$y)", glyphs.any { abs(it.x - x) < .05f && abs(it.y - y) < .05f })
        return glyphs.map { glyph ->
            val along = (glyph.x - x) * dx + (glyph.y - y) * dy
            val across = -(glyph.x - x) * dy + (glyph.y - y) * dx
            Triple(glyph, along, across)
        }.filter { (_, along, across) -> along >= -.05f && along < length && abs(across) < .05f }
            .sortedBy { it.second }.joinToString("") { it.first.unicode.orEmpty() }
    }

    private fun dump(result: ExtractionResult): String = buildString {
        append("\nmode=${result.mode}; completed=${result.completed}\n")
        result.records.filter { it.kind == RecordKind.LABEL && it.column in setOf(LabelColumn.DEVICE, LabelColumn.FUNCTION) }
            .forEach { append("${it.column}: device=${it.deviceId}, text=${it.text}, page=${it.page}, xy=${it.x},${it.y}, instance=${it.installationId}\n") }
        result.issues.forEach { append("issue: page=${it.page}, ${it.message}\n") }
    }

    private fun withFixture(block: (ImportedDocument) -> Unit) {
        val id = UUID.randomUUID().toString()
        val file = File(cacheDirectory, "native-layout-$id.pdf")
        requireNotNull(javaClass.getResourceAsStream("/fixtures/layout-cabinet-native.pdf")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try { block(ImportedDocument(id, "Native layout.pdf", file.absolutePath, file.length(), 0L)) }
        finally { file.delete() }
    }
}
