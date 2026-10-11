package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxExporterTest {
    private var serial = 0

    private fun record(column: LabelColumn, layout: String, text: String,
        kind: RecordKind = RecordKind.LABEL) = LabelRecord("record-${serial++}", column, layout, text, kind = kind)

    private fun group(column: LabelColumn, layout: String, vararg values: String): List<LabelRecord> = listOf(
        record(column, layout, layout, RecordKind.GROUP),
        record(column, layout, "印度预热压预热压", RecordKind.FILE_LABEL),
    ) + values.map { record(column, layout, it) }

    private fun result(mode: ExtractionMode, records: List<LabelRecord>) = ExtractionResult(
        "result", "doc", "印度预热压.pdf", mode, 1L, records, emptyList(), 4023,
        rules = RuleSettings(),
    )

    @Test fun quantityCountsHeadersDuplicatesAndSparesWithIndependentCumulativeRanges() {
        val records = group(LabelColumn.DEVICE, "C01", "XT01", "XT01") +
            group(LabelColumn.DEVICE, "C02", "UM01") +
            group(LabelColumn.FUNCTION, "C01", "主电源") +
            group(LabelColumn.CN1, "C02") +
            record(LabelColumn.CN1, "C02", "UM01", RecordKind.UM_HEADER) +
            record(LabelColumn.CN1, "C02", "-UM01:CN1:1/-XT01:1") +
            record(LabelColumn.CN1, "C02", "-UM01:CN1:2/-XT01:2") +
            List(4) { record(LabelColumn.CN1, "C02", "备用", RecordKind.SPARE) } +
            group(LabelColumn.YELLOW, "C09", "UM99") +
            group(LabelColumn.FIXTURE, "C09", "XT99")
        assertEquals(listOf("三列数量", "C01至C02:1-4", "C02至本列末尾:5-7",
            "单列数量", "C01至本列末尾:1-3", "CN1号码数量", "C02至本列末尾:1-9"),
            XlsxExporter.buildQuantityRecords(records).map { it.text })
    }

    @Test fun quantitiesAreRecomputedAfterEditingOrDeletingResults() {
        val records = group(LabelColumn.DEVICE, "C01", "XT01", "XT02")
        val old = XlsxExporter.recomputeQuantities(result(ExtractionMode.FULL, records))
        val changed = old.copy(records = old.records.filterNot { it.text == "XT02" })
        val cells = cells(entries(changed).getValue("xl/worksheets/sheet1.xml"))
        assertEquals("C01至本列末尾:1-3", cells["F3"])
        assertFalse(cells.values.any { it.endsWith(":1-4") })
    }

    @Test fun allSingleModesWriteOneColumnAndQuantityDoesNotLeakItsDependencies() {
        val records = LabelColumn.entries.filterNot { it == LabelColumn.QUANTITY }.flatMap {
            group(it, "C01", "${it.letter}-记录")
        }
        ExtractionMode.entries.filterNot { it == ExtractionMode.FULL }.forEach { mode ->
            val sheet = entries(result(mode, records)).getValue("xl/worksheets/sheet1.xml")
            val values = cells(sheet)
            assertEquals(mode.columns.single().title, values["A1"])
            assertTrue(values.keys.all { it.startsWith("A") })
            if (mode == ExtractionMode.QUANTITY) {
                assertEquals("三列数量", values["A2"])
                assertFalse(values.values.contains("A-记录"))
            } else {
                assertTrue(values.values.contains("${mode.columns.single().letter}-记录"))
            }
        }
    }

    @Test fun fullOutputHasSixIndependentColumnsAndSafeLiteralText() {
        val hostile = "=HYPERLINK(\"https://example.invalid\",\"001<>&\")"
        val records = group(LabelColumn.DEVICE, "C01", "0001", hostile) +
            group(LabelColumn.FUNCTION, "C02", "加热 & 冷却 <输入>")
        val files = entries(result(ExtractionMode.FULL, records))
        val sheet = xml(files.getValue("xl/worksheets/sheet1.xml"))
        val values = cells(files.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(listOf("三列", "单列", "黄标", "CN1号码", "固定座", "数量说明"),
            ('A'..'F').map { values["${it}1"] })
        assertEquals("C01", values["A2"])
        assertEquals("C02", values["B2"])
        assertEquals("印度预热压.pdf", values["A3"])
        assertEquals("印度预热压.pdf", values["B3"])
        assertEquals("0001", values["A4"])
        assertEquals(hostile, values["A5"])
        assertEquals("加热 & 冷却 <输入>", values["B4"])
        assertEquals(0, sheet.getElementsByTagName("f").length)
        assertEquals(0, sheet.getElementsByTagName("autoFilter").length)
        assertEquals(0, sheet.getElementsByTagName("pane").length)
        assertEquals(1, xml(files.getValue("xl/workbook.xml")).getElementsByTagName("sheet").length)
        assertEquals(0, xml(files.getValue("xl/styles.xml")).getElementsByTagName("b").length)
    }

    @Test fun removingLastRealCn1WireRemovesItsUmHeaderAndSpares() {
        val records = group(LabelColumn.CN1, "C01") +
            record(LabelColumn.CN1, "C01", "UM01", RecordKind.UM_HEADER) +
            record(LabelColumn.CN1, "C01", "备用", RecordKind.SPARE) +
            record(LabelColumn.CN1, "C01", "UM02", RecordKind.UM_HEADER) +
            record(LabelColumn.CN1, "C01", "-UM02:CN1:1/-XT02:1") +
            group(LabelColumn.CN1, "C02") +
            record(LabelColumn.CN1, "C02", "UM03", RecordKind.UM_HEADER) +
            record(LabelColumn.CN1, "C02", "备用", RecordKind.SPARE)
        assertEquals(listOf("C01", "印度预热压预热压", "UM02", "-UM02:CN1:1/-XT02:1"),
            XlsxExporter.exportRecords(records, LabelColumn.CN1).map { it.text })
        assertEquals("C01至本列末尾:1-4", XlsxExporter.buildQuantityRecords(records).last().text)
    }

    @Test fun sheetNameIsValidAndDestinationStreamRemainsOpen() {
        val document = result(ExtractionMode.DEVICE, emptyList()).copy(
            documentName = "包含[非法]符号和很长很长很长很长很长很长很长很长的名称.pdf")
        val files = entries(document)
        val sheet = xml(files.getValue("xl/workbook.xml")).getElementsByTagName("sheet").item(0)
        val name = sheet.attributes.getNamedItem("name").nodeValue
        assertTrue(name.length <= 31)
        assertFalse(name.contains('['))
        var closed = false
        val destination = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        XlsxExporter.write(document, destination)
        assertFalse(closed)
        assertTrue(destination.size() > 0)
        assertEquals("印度预热压.xlsx", XlsxExporter.fileName(result(ExtractionMode.FULL, emptyList())))
    }

    @Test fun allModesExportTheOriginalFilenameAndReplaceLegacyMachineLabels() {
        val data = LabelColumn.entries.filterNot { it == LabelColumn.QUANTITY }.flatMap {
            group(it, "C01", "${it.letter}-记录")
        }
        ExtractionMode.entries.forEach { mode ->
            val document = result(mode, data).copy(documentName = "B06B07-1.PDF")
            assertEquals("B06B07-1.xlsx", XlsxExporter.fileName(document))
            val values = cells(entries(document).getValue("xl/worksheets/sheet1.xml"))
            assertFalse(values.values.contains("印度预热压预热压"))
            if (mode == ExtractionMode.FULL) ('A'..'E').forEach { assertEquals("B06B07-1.PDF", values["${it}3"]) }
            else if (mode != ExtractionMode.QUANTITY) assertEquals("B06B07-1.PDF", values["A3"])
        }
    }

    private fun entries(result: ExtractionResult): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { XlsxExporter.write(result, it) }.toByteArray()
        return buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun cells(sheet: String): Map<String, String> = buildMap {
        val nodes = xml(sheet).getElementsByTagName("c")
        for (index in 0 until nodes.length) {
            val cell = nodes.item(index)
            put(cell.attributes.getNamedItem("r").nodeValue, cell.textContent)
        }
    }

    private fun xml(text: String): Document = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
}
