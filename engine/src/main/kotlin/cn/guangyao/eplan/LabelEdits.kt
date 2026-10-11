package cn.guangyao.eplan

import java.util.UUID

/** User corrections preserve source evidence and update all dependent print quantities. */
object LabelEdits {
    fun change(result: ExtractionResult, recordId: String, text: String): ExtractionResult {
        val record = result.records.firstOrNull { it.id == recordId } ?: error("标签已不存在。")
        editable(result, record)
        val clean = text.trim()
        validate(record.column, clean)
        if (record.column == LabelColumn.CN1) {
            require(EplanParsing.cn1Wire(record.text)?.um.equals(EplanParsing.cn1Wire(clean)?.um, true)) {
                "修改后的号码必须属于原 UM；其他 UM 请使用补充标签。"
            }
        }
        if (record.column == LabelColumn.FIXTURE) require(result.records.none {
            it.id != record.id && it.column == record.column && it.layout == record.layout &&
                it.kind == RecordKind.LABEL && it.text.trim().equals(clean, true)
        }) { "此柜体已存在这个固定座编号。" }
        return XlsxExporter.recomputeQuantities(result.copy(records = result.records.map {
            if (it.id == recordId) it.copy(text = clean, edited = true) else it
        }))
    }

    fun add(result: ExtractionResult, column: LabelColumn, layout: String, text: String): ExtractionResult {
        require(column in result.mode.columns && column != LabelColumn.QUANTITY) { "此提取结果不包含这一列。" }
        val clean = text.trim()
        validate(column, clean)
        require(layout.isNotBlank() && result.records.any { it.layout == layout }) { "请选择已有柜体。" }
        val values = result.records.filter { it.column == column }.toMutableList()
        var start = values.indexOfFirst { it.kind == RecordKind.GROUP && it.layout == layout }
        if (start < 0) {
            val label = DocumentNames.machineName(result.documentName)
            start = values.size
            values += LabelRecord(newId(), column, layout, layout, kind = RecordKind.GROUP)
            values += LabelRecord(newId(), column, layout, label, kind = RecordKind.FILE_LABEL)
        }
        val end = (start + 1 until values.size).firstOrNull { values[it].kind == RecordKind.GROUP } ?: values.size
        if (column == LabelColumn.FIXTURE) require(values.subList(start, end).none {
            it.kind == RecordKind.LABEL && it.text.trim().equals(clean, true)
        }) { "此柜体已存在这个固定座编号。" }
        val added = LabelRecord(newId(), column, layout, clean, edited = true, sourceText = "手动补充")
        val issues = result.issues.toMutableList()
        if (column == LabelColumn.CN1) {
            val um = EplanParsing.cn1Wire(clean)!!.um
            var header = (start + 1 until end).firstOrNull { values[it].kind == RecordKind.UM_HEADER && values[it].text.equals(um, true) }
            if (header == null) {
                header = end
                val capacity = if (result.completed) result.rules.defaultCn1Cores.coerceIn(1, 100) * EplanParsing.umAxisCount(um) else null
                values.addAll(end, listOf(LabelRecord(newId(), column, layout, um, kind = RecordKind.UM_HEADER, cn1Capacity = capacity), added))
            } else {
                val groupEnd = umEnd(values, header)
                val capacity = knownCapacity(values, header, groupEnd)
                values[header] = values[header].copy(cn1Capacity = capacity)
                val firstSpare = (header + 1 until groupEnd).firstOrNull { values[it].kind == RecordKind.SPARE } ?: groupEnd
                values.add(firstSpare, added)
            }
            adjustSpares(values, header, result, issues)
        } else values.add(end, added)
        issues += ExtractionIssue("已手动补充 ${column.title}：$clean，位置在所选分组末尾；请核对打印顺序及相关列。", layout = layout)
        return replaceColumn(result, column, values, issues)
    }

    fun remove(result: ExtractionResult, recordId: String): ExtractionResult {
        val target = result.records.firstOrNull { it.id == recordId } ?: return result
        editable(result, target)
        // Work on one column so UM boundaries can never span unrelated columns or cabinets.
        val values = result.records.filter { it.column == target.column }.toMutableList()
        val index = values.indexOfFirst { it.id == recordId }
        val issues = result.issues.toMutableList()
        var header: Int? = null
        if (target.column == LabelColumn.CN1) {
            val groupStart = (index - 1 downTo 0).firstOrNull { values[it].kind == RecordKind.GROUP } ?: -1
            header = (index - 1 downTo groupStart + 1).firstOrNull { values[it].kind == RecordKind.UM_HEADER }
            if (header != null) values[header] = values[header].copy(cn1Capacity = knownCapacity(values, header, umEnd(values, header)))
        }
        values.removeAt(index)
        if (header != null) adjustSpares(values, header, result, issues)
        issues += ExtractionIssue("已手动删除 ${target.column.title}：${target.text}。", target.page, target.layout)
        // This also removes an empty UM/header/spares when its final real wire disappears.
        return replaceColumn(result, target.column, values, issues)
    }

    private fun editable(result: ExtractionResult, record: LabelRecord) {
        require(record.column in result.mode.columns && record.kind == RecordKind.LABEL && record.column != LabelColumn.QUANTITY) {
            "分组标题、备用、数量和未选择的列不能单独修改。"
        }
    }

    private fun replaceColumn(result: ExtractionResult, column: LabelColumn, values: List<LabelRecord>, issues: List<ExtractionIssue>) =
        XlsxExporter.recomputeQuantities(result.copy(records = LabelColumn.entries.flatMap {
            if (it == column) XlsxExporter.exportRecords(values, column) else result.records.filter { record -> record.column == it }
        }, issues = issues))

    private fun umEnd(values: List<LabelRecord>, header: Int): Int = (header + 1 until values.size).firstOrNull {
        values[it].kind == RecordKind.UM_HEADER || values[it].kind == RecordKind.GROUP
    } ?: values.size

    /** Legacy groups are inferable only when actual spare records prove a known capacity. */
    private fun knownCapacity(values: List<LabelRecord>, header: Int, end: Int): Int? {
        values[header].cn1Capacity?.takeIf { it > 0 }?.let { return it }
        val group = values.subList(header + 1, end)
        return if (group.any { it.kind == RecordKind.SPARE }) group.count { it.kind == RecordKind.LABEL || it.kind == RecordKind.SPARE } else null
    }

    private fun adjustSpares(values: MutableList<LabelRecord>, header: Int, result: ExtractionResult, issues: MutableList<ExtractionIssue>) {
        val end = umEnd(values, header)
        val count = values.subList(header + 1, end).count { it.kind == RecordKind.LABEL }
        if (count == 0 || !result.rules.addSpares || !result.completed) return
        val owner = values[header]
        val capacity = owner.cn1Capacity?.takeIf { it > 0 }
        if (capacity == null) {
            issues += ExtractionIssue("${owner.text} 的容量尚未确认，本次修改未自动补备用。", owner.page, owner.layout)
            return
        }
        val previous = values.subList(header + 1, end).filter { it.kind == RecordKind.SPARE }
        for (index in end - 1 downTo header + 1) if (values[index].kind == RecordKind.SPARE) values.removeAt(index)
        val spares = List((capacity - count).coerceAtLeast(0)) { index ->
            previous.getOrNull(index) ?: LabelRecord(newId(), LabelColumn.CN1, owner.layout, "备用",
                kind = RecordKind.SPARE, sourceText = "按已确认的 UM 容量补齐", edited = true)
        }
        values.addAll(umEnd(values, header), spares)
        if (count > capacity) issues += ExtractionIssue("${owner.text} 有 $count 条完整号码，超过 $capacity 芯；已保留全部号码并停止补备用。", owner.page, owner.layout)
    }

    private fun validate(column: LabelColumn, text: String) {
        require(text.isNotBlank() && text.length <= 500 && text.none { it.isISOControl() }) { "标签不能为空，最多 500 字且不能包含换行。" }
        when (column) {
            LabelColumn.DEVICE -> require(EplanParsing.deviceId(text, false) == text && !text.startsWith('-')) { "请输入不带开头减号或空格的完整设备编号。" }
            LabelColumn.YELLOW -> require(EplanParsing.isUm(text)) { "黄标必须是 UM 设备编号。" }
            LabelColumn.FIXTURE -> require(EplanParsing.deviceId(text, false) == text && !text.startsWith('-') && EplanParsing.isXt(text)) { "固定座必须是不带开头减号的 XT 设备编号。" }
            LabelColumn.CN1 -> require(EplanParsing.cn1Wire(text) != null) { "请输入含 :CN1: 的完整双端线号。" }
            LabelColumn.QUANTITY -> error("数量说明由程序计算。")
            else -> Unit
        }
    }

    private fun newId() = UUID.randomUUID().toString()
}
