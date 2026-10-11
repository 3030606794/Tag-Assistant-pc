package cn.guangyao.eplan

import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Plain, single-sheet OOXML. User/PDF text is never interpreted as an Excel formula. */
object XlsxExporter {
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private val countedColumns = listOf(LabelColumn.DEVICE, LabelColumn.FUNCTION, LabelColumn.CN1)

    fun fileName(result: ExtractionResult): String = "${baseName(result)}.xlsx"

    fun buildQuantityRecords(records: List<LabelRecord>): List<LabelRecord> = buildList {
        countedColumns.forEach { column ->
            val data = exportRecords(records, column)
            val starts = data.indices.filter { data[it].kind == RecordKind.GROUP }
            add(LabelRecord("quantity-${column.name}-title", LabelColumn.QUANTITY, "",
                "${column.title}数量", kind = RecordKind.GROUP))
            var total = 0
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: data.size
                val group = data[start]
                val nextName = starts.getOrNull(index + 1)?.let { data[it].text } ?: "本列末尾"
                val rangeStart = total + 1
                total += end - start
                add(LabelRecord("quantity-${column.name}-${group.id}", LabelColumn.QUANTITY,
                    group.layout, "${group.text}至$nextName:$rangeStart-$total", page = group.page,
                    x = group.x, y = group.y, width = group.width, height = group.height))
            }
        }
    }

    fun recomputeQuantities(result: ExtractionResult): ExtractionResult {
        if (LabelColumn.QUANTITY !in result.mode.columns) return result
        val old = result.records.filter { it.column == LabelColumn.QUANTITY }.associateBy { it.id }
        val calculated = buildQuantityRecords(result.records).map { record ->
            record.copy(favorite = old[record.id]?.favorite ?: false)
        }
        return result.copy(records = result.records.filter { it.column != LabelColumn.QUANTITY } + calculated)
    }

    /** Does not close the caller's stream; the caller owns the destination URI descriptor. */
    fun write(result: ExtractionResult, output: OutputStream) {
        val selected = result.mode.columns
        val quantity = buildQuantityRecords(result.records)
        val values = selected.map { column ->
            if (column == LabelColumn.QUANTITY) quantity else exportRecords(result.records, column)
        }
        val rowCount = values.maxOfOrNull { it.size } ?: 0
        require(rowCount < 1_048_576) { "数据超过 Excel 单表行数限制，请按柜体分批导出。" }
        require(values.all { column -> column.all { it.text.length <= 32_767 } }) { "标签文字超过 Excel 单元格长度限制，请先编辑。" }
        val machineName = DocumentNames.machineName(result.documentName)
        require(machineName.length <= 32_767) { "文件名超过 Excel 单元格长度限制。" }
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = flush()
        }, Charsets.UTF_8).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", XML + """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
              <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            </Types>
        """.trimIndent())
        entry("_rels/.rels", XML + """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
        """.trimIndent())
        entry("xl/workbook.xml", XML + """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="${xml(sheetName(result))}" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
        """.trimIndent())
        entry("xl/_rels/workbook.xml.rels", XML + """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>
        """.trimIndent())
        entry("xl/styles.xml", XML + """
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
              <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
              <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
              <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
              <cellXfs count="1"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
              <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
        """.trimIndent())
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write(XML)
        writer.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val lastColumn = ('A'.code + selected.lastIndex).toChar()
        writer.write("<dimension ref=\"A1:$lastColumn${rowCount + 1}\"/><cols>")
        selected.forEachIndexed { index, column ->
            val width = when (column) {
                LabelColumn.CN1 -> 64
                LabelColumn.QUANTITY -> 42
                LabelColumn.FUNCTION -> 32
                else -> 25
            }
            writer.write("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
        }
        writer.write("</cols><sheetData>")
        for (row in 0..rowCount) {
            writer.write("<row r=\"${row + 1}\">")
            selected.forEachIndexed { index, column ->
                val record = if (row == 0) null else values[index].getOrNull(row - 1)
                val value = if (row == 0) column.title else record?.let {
                    if (it.kind == RecordKind.FILE_LABEL) machineName else it.text
                }
                if (value != null) {
                    val reference = "${('A'.code + index).toChar()}${row + 1}"
                    writer.write("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")
                }
            }
            writer.write("</row>")
        }
        writer.write("</sheetData></worksheet>")
        writer.flush()
        zip.closeEntry()
        zip.finish()
        zip.flush()
        }
    }

    /** Empty groups disappear after the last real label is removed; CN1 spares are not evidence. */
    internal fun exportRecords(records: List<LabelRecord>, column: LabelColumn): List<LabelRecord> {
        val data = records.filter { it.column == column && it.text.isNotBlank() }
        val starts = data.indices.filter { data[it].kind == RecordKind.GROUP }
        if (starts.isEmpty()) return data
        return buildList {
            starts.forEachIndexed { index, start ->
                var group = data.subList(start, starts.getOrNull(index + 1) ?: data.size)
                if (column == LabelColumn.CN1) {
                    val umStarts = group.indices.filter { group[it].kind == RecordKind.UM_HEADER }
                    if (umStarts.isNotEmpty()) group = buildList {
                        addAll(group.take(umStarts.first()))
                        umStarts.forEachIndexed { umIndex, umStart ->
                            val um = group.subList(umStart, umStarts.getOrNull(umIndex + 1) ?: group.size)
                            if (um.any { it.kind == RecordKind.LABEL }) addAll(um)
                        }
                    }
                }
                if (group.any { it.kind == RecordKind.LABEL }) addAll(group)
            }
        }
    }

    private fun baseName(result: ExtractionResult): String {
        val name = DocumentNames.exportBaseName(result.documentName)
        return name.replace(Regex("[\\x00-\\x1f<>:\"/\\\\|?*]"), "_").trim().trimEnd('.')
            .take(180).ifBlank { "电气图纸标签" }
    }

    private fun sheetName(result: ExtractionResult): String = baseName(result)
        .replace(Regex("[\\[\\]:*?/\\\\]"), "_").trim('\'').take(31).trimEnd('\'').ifBlank { "标签" }

    private fun xml(text: String): String = buildString(text.length + 16) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> when {
                    char == '\t' || char == '\n' || char == '\r' -> append(char)
                    char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                        append(char).append(text[++index])
                    }
                    char >= ' ' && !char.isSurrogate() && char != '\uFFFE' && char != '\uFFFF' -> append(char)
                }
            }
            index++
        }
    }
}
