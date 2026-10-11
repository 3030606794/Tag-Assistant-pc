package cn.guangyao.eplan

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Generated synthetic documents exercise the actual Apache PDFBox decoder. No customer drawings are fixtures. */
class OfflinePdfEngineTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    @Test fun nativePdfProducesInstallationDuplicatesUmSubsetAndUniqueFixtures() {
        val imported = syntheticPdf()
        val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.FULL, rules(), {}, { false })
        assertTrue(result.completed)
        assertEquals(3, result.pageCount)
        assertEquals(listOf("UM01", "1XT01", "1XT01"), labels(result, LabelColumn.DEVICE))
        assertEquals(listOf("UM01"), labels(result, LabelColumn.YELLOW))
        assertEquals(listOf("1XT01"), labels(result, LabelColumn.FIXTURE))
        assertEquals(listOf("UM01:CN1:5/1XT01:1.1", "1XT01:2.1/UM01:CN1:13"), labels(result, LabelColumn.CN1))
        assertEquals(4, result.records.count { it.column == LabelColumn.CN1 && it.kind == RecordKind.SPARE })
        assertEquals(6, result.records.single { it.kind == RecordKind.UM_HEADER }.cn1Capacity)
        assertTrue(result.records.filter { it.kind == RecordKind.LABEL }.all { it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite() })
        assertTrue(result.records.filter { it.column == LabelColumn.DEVICE && it.kind == RecordKind.LABEL }.all { it.width > 0f && it.height > 0f && it.page == 1 })
    }

    @Test fun cn1OnlySkipsLayoutsAndDoesNotReadHolePage() {
        val imported = syntheticPdf()
        val messages = mutableListOf<JobProgress>()
        val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.CN1, rules(), { messages += it }, { false })
        assertTrue(result.records.all { it.column == LabelColumn.CN1 })
        assertEquals(2, labels(result, LabelColumn.CN1).size)
        assertFalse(messages.any { it.message.contains("读取实际安装位置") })
        assertFalse(messages.any { it.page == 3 })
        assertFalse(result.records.any { it.text.contains("SHOULD_NOT_APPEAR") })
    }

    @Test fun cancelRetainsCompletedLayoutWithoutInventingSpares() {
        val imported = syntheticPdf()
        var cancelled = false
        val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.FULL, rules(), {
            if (it.message.contains("读取原理图")) cancelled = true
        }, { cancelled })
        assertFalse(result.completed)
        assertEquals(3, labels(result, LabelColumn.DEVICE).size)
        assertFalse(result.records.any { it.kind == RecordKind.SPARE })
        assertTrue(result.issues.any { it.message.contains("取消") })
    }

    @Test fun manualFunctionDoesNotInventDefaultForAnotherLayoutDevice() {
        val imported = syntheticPdf(devices = listOf("1PS01", "2PS02"))
        val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.FUNCTION,
            rules().copy(functionOverrides = "BOX/1PS01=PLC电源模块"), {}, { false })
        assertEquals(listOf("PLC电源模块"), labels(result, LabelColumn.FUNCTION))
        assertTrue(result.issues.any { it.message.contains("2PS02") && it.message.contains("布局图") })
    }

    @Test fun pageFrameCompletesMissingCabinetInOtherwiseValidPageIndex() {
        val imported = syntheticPdf(includeCabinetOutline = false)
        val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.DEVICE, rules(), {}, { false })
        assertEquals(listOf("UM01", "1XT01", "1XT01"), labels(result, LabelColumn.DEVICE))
        assertEquals(setOf("BOX"), result.records.map { it.layout }.toSet())
    }

    @Test fun unrelatedNearbyCableCapacityCannotOverrideConfiguredCn1Default() {
        val imported = syntheticPdf(cableNote = "4x0.5")
        val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.CN1, rules(), {}, { false })
        assertEquals(6, result.records.single { it.kind == RecordKind.UM_HEADER }.cn1Capacity)
        assertEquals(4, result.records.count { it.kind == RecordKind.SPARE })
        assertTrue(result.issues.any { it.message.contains("未能确认属于此 CN1") })
    }

    @Test fun fileLabelCopiesExactPdfNameIncludingExtensionWithoutReadingCover() {
        val imported = syntheticPdf().copy(name = " B06B07-1 最终版.PDF ")
        val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.FULL, rules(), {}, { false })
        val fileLabels = result.records.filter { it.kind == RecordKind.FILE_LABEL }
        assertTrue(fileLabels.isNotEmpty())
        assertTrue(fileLabels.all { it.text == imported.name })
        assertFalse(result.issues.any { it.message.contains("机台简称") || it.message.contains("总封面") })
    }

    private fun rules() = RuleSettings()
    private fun labels(result: ExtractionResult, column: LabelColumn) = result.records.filter { it.column == column && it.kind == RecordKind.LABEL }.map { it.text }

    private fun syntheticPdf(
        devices: List<String> = listOf("UM01", "1XT01", "1XT01"),
        includeCabinetOutline: Boolean = true,
        cableNote: String = "",
    ): ImportedDocument {

        val name = UUID.randomUUID().toString()
        val file = File(cacheDirectory, "$name.pdf")
        PDDocument().use { pdf ->
            val layout = PDPage(PDRectangle(400f, 600f))
            val schematic = PDPage(PDRectangle(400f, 600f))
            val hole = PDPage(PDRectangle(400f, 600f))
            listOf(layout, schematic, hole).forEach(pdf::addPage)
            PDPageContentStream(pdf, layout).use { stream ->
                stream.setNonStrokingColor(0, 0, 255)
                devices.forEachIndexed { index, device ->
                    text(stream, "-$device", if (index == 2) 180f else 30f, if (index == 0) 520f else 420f)
                }
                text(stream, "+BOX", 300f, 30f)
            }
            PDPageContentStream(pdf, schematic).use { stream ->
                stream.setNonStrokingColor(255, 0, 0)
                text(stream, "UM01:CN1:5/1XT01:1.1", 30f, 420f)
                text(stream, "1XT01:2.1/UM01:CN1:13", 30f, 380f)
                text(stream, "-UM01:CN1:4", 30f, 300f)
                text(stream, "UM01:CN1:4 / 3.2", 30f, 240f)
                if (cableNote.isNotBlank()) text(stream, cableNote, 30f, 455f)
                text(stream, "+BOX", 300f, 30f)
            }
            PDPageContentStream(pdf, hole).use { stream -> text(stream, "-UM99", 30f, 400f) }
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            val higher = PDOutlineItem().apply { title = "=TEST Synthetic" }
            val cabinet = PDOutlineItem().apply { title = if (includeCabinetOutline) "+BOX Main cabinet" else "Panel document set" }
            val layoutType = PDOutlineItem().apply { title = "&ELU1 Installation" }
            val schematicType = PDOutlineItem().apply { title = "&EFSI Wiring" }
            outline.addLast(higher); higher.addLast(cabinet)
            cabinet.addLast(layoutType); cabinet.addLast(schematicType)
            layoutType.addLast(PDOutlineItem().apply { title = "1 Main cabinet"; setDestination(layout) })
            layoutType.addLast(PDOutlineItem().apply { title = "2 安装板孔位图"; setDestination(hole) })
            schematicType.addLast(PDOutlineItem().apply { title = "1 Servo wiring"; setDestination(schematic) })
            pdf.save(file)
        }
        return ImportedDocument(name, "Synthetic.pdf", file.absolutePath, file.length(), 0L)
    }

    private fun text(stream: PDPageContentStream, value: String, x: Float, y: Float) {
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(x, y)
        stream.showText(value)
        stream.endText()
    }
}
