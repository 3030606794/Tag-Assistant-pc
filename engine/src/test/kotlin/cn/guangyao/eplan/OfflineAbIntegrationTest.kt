package cn.guangyao.eplan

import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** End-to-end Android decoder regressions using generated pages, never customer drawings. */
class OfflineAbIntegrationTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root


    private val phases = listOf("L1", "L2", "L3", "PE")

    @Test fun supplementaryResistorViewIsAppendedEvenWhenPdfListsItBeforeTheActualLayout() {
        val document = fixture(secondAsLayout = true) { pdf, supplement ->
            val actual = PDPage(PDRectangle(400f, 600f)).also(pdf::addPage)
            for (page in listOf(supplement, actual)) PDPageContentStream(pdf, page).use { stream ->
                listOf("R01", "R02").forEachIndexed { index, id ->
                    stream.setNonStrokingColor(0, 0, 0)
                    text(stream, "-$id", 50f + index * 140f, 520f)
                    stream.setNonStrokingColor(0, 0, 255)
                    text(stream, "L${index + 1}", 50f + index * 140f, 490f)
                }
            }
            actual
        }
        try {
            val result = extract(document, ExtractionMode.FULL)
            for (column in listOf(LabelColumn.DEVICE, LabelColumn.FUNCTION)) {
                val rows = labels(result, column)
                assertEquals("$column retains both views, actual layout first", listOf(2, 2, 1, 1), rows.map { it.page })
                assertEquals(4, rows.map { it.installationId }.distinct().size)
            }
        } finally { File(document.path).delete() }
    }

    @Test fun approvedModuleNamesApplyPerInstallationOnlyWhenNativeAndManualCaptionsAreMissing() {
        val ids = listOf("ECT01", "ECT01", "AI101", "AO101", "DI101", "DO101", "IM01", "PS01")
        val document = fixture { pdf, layout ->
            PDPageContentStream(pdf, layout).use { stream ->
                ids.forEachIndexed { index, id ->
                    val y = 530f - index * 58f
                    stream.setNonStrokingColor(0, 0, 0)
                    text(stream, "-$id", 40f, y)
                    if (id == "DI101") {
                        stream.setNonStrokingColor(0, 0, 255)
                        text(stream, "L1", 40f, y - 20f)
                    }
                }
            }
            null
        }
        try {
            val result = OfflinePdfEngine(cacheDirectory).extract(document, ExtractionMode.FULL,
                RuleSettings(functionOverrides = "BOX/AI101=Custom input"), {}, { false })
            val devices = labels(result, LabelColumn.DEVICE)
            val functions = labels(result, LabelColumn.FUNCTION)
            assertEquals(ids, devices.map { it.text })
            assertEquals(listOf("耦合器", "耦合器", "Custom input", "模拟量输出", "L1", "数字量输出"), functions.map { it.text })
            assertEquals(listOf("user-module-label", "user-module-label", "rule-override", "user-module-label", "installation-label", "user-module-label"),
                functions.map { it.evidence })
            assertEquals(devices.take(6).map { it.installationId }, functions.map { it.installationId })
            assertTrue(functions.all { it.sourceText.startsWith('-') || it.sourceText == "L1" })
            assertFalse(functions.any { it.deviceId in setOf("IM01", "PS01") })
        } finally { File(document.path).delete() }
    }

    @Test fun fullExtractionKeepsFourPhysicalTerminalsAndTheirOwnFunctions() {
        val document = repeatedTerminalPdf()
        try {
            val result = extract(document, ExtractionMode.FULL)
            val devices = labels(result, LabelColumn.DEVICE)
            val functions = labels(result, LabelColumn.FUNCTION)

            assertTrue(result.completed)
            assertEquals(List(4) { "1XT20" }, devices.map { it.text })
            assertEquals(phases, functions.map { it.text })
            assertEquals(4, devices.map { it.installationId }.distinct().size)
            assertTrue(devices.all { it.installationId.isNotBlank() && it.deviceId == "1XT20" })
            assertEquals(devices.map { it.installationId }, functions.map { it.installationId })
            assertTrue(functions.all { it.deviceId == "1XT20" && it.evidence == "installation-label" })
            assertTrue(devices.zipWithNext().all { (left, right) -> left.x < right.x })
        } finally {
            File(document.path).delete()
        }
    }

    @Test fun singleModesAndCachedRerunsRetainTheSamePhysicalIdentities() {
        val document = repeatedTerminalPdf()
        try {
            val aOnly = extract(document, ExtractionMode.DEVICE)
            assertTrue(aOnly.records.all { it.column == LabelColumn.DEVICE })
            val firstDevices = labels(aOnly, LabelColumn.DEVICE)
            assertEquals(List(4) { "1XT20" }, firstDevices.map { it.text })
            assertTrue("The next extraction must have a persisted page cache available",
                File(cacheDirectory, "offline-pdf").listFiles().orEmpty()
                    .filter { it.name.startsWith("${document.id}-") }
                    .any { File(it, "1.json").isFile })

            val bOnly = extract(document, ExtractionMode.FUNCTION)
            assertTrue(bOnly.records.all { it.column == LabelColumn.FUNCTION })
            val firstFunctions = labels(bOnly, LabelColumn.FUNCTION)
            assertEquals(phases, firstFunctions.map { it.text })
            assertEquals(firstDevices.map { it.installationId }, firstFunctions.map { it.installationId })

            val full = extract(document, ExtractionMode.FULL)
            assertEquals(firstDevices.map { it.installationId }, labels(full, LabelColumn.DEVICE).map { it.installationId })
            assertEquals(firstFunctions.map { it.installationId }, labels(full, LabelColumn.FUNCTION).map { it.installationId })
            assertEquals(phases, labels(full, LabelColumn.FUNCTION).map { it.text })

            val rerun = extract(document, ExtractionMode.FUNCTION)
            assertEquals(firstFunctions.map { Triple(it.installationId, it.deviceId, it.text) },
                labels(rerun, LabelColumn.FUNCTION).map { Triple(it.installationId, it.deviceId, it.text) })
        } finally {
            File(document.path).delete()
        }
    }

    @Test fun schematicLinksCannotSupplyMissingLayoutFunctions() {
        val document = linkedDevicePdf()
        try {
            val messages = mutableListOf<JobProgress>()
            val result = OfflinePdfEngine(cacheDirectory).extract(document, ExtractionMode.FUNCTION, RuleSettings(), { messages += it }, { false })
            val aOnly = extract(document, ExtractionMode.DEVICE)
            val device = labels(aOnly, LabelColumn.DEVICE).single()

            assertTrue(result.completed)
            assertEquals("UM109", device.text)
            assertTrue(labels(result, LabelColumn.FUNCTION).isEmpty())
            assertFalse(messages.any { it.page == 2 })

            val cached = labels(extract(document, ExtractionMode.FUNCTION), LabelColumn.FUNCTION)
            assertTrue(cached.isEmpty())
            assertEquals(device.installationId, labels(extract(document, ExtractionMode.FULL), LabelColumn.DEVICE).single().installationId)
        } finally {
            File(document.path).delete()
        }
    }

    @Test fun legacyAllBlackCacheCannotHideBlueInstallationFunctionsAfterUpgrade() {
        val document = repeatedTerminalPdf()
        try {
            val old = File(cacheDirectory, "offline-pdf/${document.id}-${document.bytes}-${File(document.path).lastModified()}-v2").apply { mkdirs() }
            File(old, "1.json").writeText("""{"width":400,"height":600,"boxes":[["-UM999",40,80,50,10,40,90,0,10,false]]}""")
            val result = extract(document, ExtractionMode.FULL)
            assertEquals(List(4) { "1XT20" }, labels(result, LabelColumn.DEVICE).map { it.text })
            assertEquals(phases, labels(result, LabelColumn.FUNCTION).map { it.text })
        } finally { File(document.path).delete() }
    }

    @Test fun explicitLayoutFunctionWinsOverDifferentLinkedFunctionAutomatically() {
        val document = linkedDevicePdf(layoutFunction = "L1")
        try {
            val function = labels(extract(document, ExtractionMode.FUNCTION), LabelColumn.FUNCTION).single()
            assertEquals("L1", function.text)
            assertEquals("installation-label", function.evidence)
            assertEquals(1, function.page)
        } finally { File(document.path).delete() }
    }

    private fun extract(document: ImportedDocument, mode: ExtractionMode): ExtractionResult =
        OfflinePdfEngine(cacheDirectory).extract(document, mode, RuleSettings(), {}, { false })

    private fun labels(result: ExtractionResult, column: LabelColumn): List<LabelRecord> =
        result.records.filter { it.column == column && it.kind == RecordKind.LABEL }

    private fun repeatedTerminalPdf(): ImportedDocument = fixture { pdf, layout ->
        PDPageContentStream(pdf, layout).use { stream ->
            phases.forEachIndexed { index, phase ->
                val x = 40f + index * 80f
                stream.setNonStrokingColor(0, 0, 0)
                text(stream, "-1XT20", x, 520f)
                stream.setNonStrokingColor(0, 0, 255)
                text(stream, phase, x, 490f)
            }
            stream.setNonStrokingColor(0, 0, 0)
            text(stream, "+BOX", 300f, 30f)
        }
        null
    }

    private fun linkedDevicePdf(layoutFunction: String? = null): ImportedDocument = fixture { pdf, layout ->
        val schematic = PDPage(PDRectangle(400f, 600f)).also(pdf::addPage)
        PDPageContentStream(pdf, layout).use { stream ->
            text(stream, "-UM109", 40f, 520f)
            if (layoutFunction != null) {
                stream.setNonStrokingColor(0, 0, 255)
                text(stream, layoutFunction, 40f, 490f)
                stream.setNonStrokingColor(0, 0, 0)
            }
            text(stream, "+BOX", 300f, 30f)
        }
        PDPageContentStream(pdf, schematic).use { stream ->
            text(stream, "-UM109", 100f, 530f)
            stream.setNonStrokingColor(0, 0, 255)
            text(stream, "STO", 140f, 495f)
            // PE is near this device but outside the explicitly highlighted object.
            text(stream, "PE", 60f, 525f)
            stream.setNonStrokingColor(0, 0, 0)
            text(stream, "+BOX", 300f, 30f)
        }
        val action = PDActionGoTo().apply {
            destination = PDPageFitDestination().apply { page = schematic }
            cosObject.setItem(COSName.getPDFName("Next"), COSDictionary().apply {
                setName(COSName.S, "JavaScript")
                setString(COSName.getPDFName("JS"), "highlight(1,[80,450,200,560]);")
            })
        }
        layout.annotations = listOf(PDAnnotationLink().apply {
            rectangle = PDRectangle(38f, 518f, 60f, 16f)
            this.action = action
        })
        schematic
    }

    private fun fixture(secondAsLayout: Boolean = false, content: (PDDocument, PDPage) -> PDPage?): ImportedDocument {

        val id = UUID.randomUUID().toString()
        val file = File(cacheDirectory, "ab-$id.pdf")
        PDDocument().use { pdf ->
            val layout = PDPage(PDRectangle(400f, 600f)).also(pdf::addPage)
            val schematic = content(pdf, layout)
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            val higher = PDOutlineItem().apply { title = "=SYNTHETIC AB regression" }
            val cabinet = PDOutlineItem().apply { title = "+BOX Main cabinet" }
            val installationType = PDOutlineItem().apply { title = "&ELU1 Installation" }
            outline.addLast(higher)
            higher.addLast(cabinet)
            cabinet.addLast(installationType)
            installationType.addLast(PDOutlineItem().apply { title = if (secondAsLayout) "1 电阻原理图" else "1 Main cabinet"; setDestination(layout) })
            if (schematic != null && secondAsLayout) {
                installationType.addLast(PDOutlineItem().apply { title = "2 安装板布局图"; setDestination(schematic) })
            } else if (schematic != null) {
                val wiringType = PDOutlineItem().apply { title = "&EFSI Wiring" }
                cabinet.addLast(wiringType)
                wiringType.addLast(PDOutlineItem().apply { title = "1 Servo wiring"; setDestination(schematic) })
            }
            pdf.save(file)
        }
        return ImportedDocument(id, "AB Synthetic.pdf", file.absolutePath, file.length(), 0L)
    }

    private fun text(stream: PDPageContentStream, value: String, x: Float, y: Float) {
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(x, y)
        stream.showText(value)
        stream.endText()
    }
}
