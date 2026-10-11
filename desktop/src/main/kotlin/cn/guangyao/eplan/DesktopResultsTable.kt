@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package cn.guangyao.eplan

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopResultsTable(result: ExtractionResult?, canUndo: Boolean, actions: DesktopActions, onSource: (LabelRecord) -> Unit, modifier: Modifier = Modifier) {
    if (result == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol("view_column", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("从一份图纸开始", style = MaterialTheme.typography.headlineSmall)
                Text("导入 PDF，选择完整或单项提取。结果会自动保存。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = actions.importPdf, shape = RoundedCornerShape(10.dp)) { Text("导入 PDF · Ctrl+O") }
            }
        }
        return
    }
    var query by remember(result.id) { mutableStateOf("") }
    var column by remember(result.id) { mutableStateOf<LabelColumn?>(null) }
    var selected by remember(result.id) { mutableStateOf(emptySet<String>()) }
    var anchor by remember(result.id) { mutableStateOf<String?>(null) }
    var focusedRow by remember(result.id) { mutableStateOf<String?>(null) }
    var editing by remember(result.id) { mutableStateOf<String?>(null) }
    var addOpen by remember(result.id) { mutableStateOf(false) }
    var deleteOpen by remember(result.id) { mutableStateOf(false) }
    var issuesOpen by remember(result.id) { mutableStateOf(false) }
    var queryFocused by remember { mutableStateOf(false) }
    var controlClick by remember { mutableStateOf(false) }
    var shiftClick by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val tableFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val tableHorizontal = rememberScrollState()
    val visible = remember(result.records, query, column) {
        val search = query.trim()
        result.records.filter { record -> record.column in result.mode.columns && (column == null || record.column == column) &&
            (search.isBlank() || record.text.contains(search, true) || record.layout.contains(search, true)) }
    }
    val originalOrder = remember(result.records) { result.records.mapIndexed { index, record -> record.id to index + 1 }.toMap() }
    val deletable = remember(result.records, selected) { result.records.filter { it.id in selected && it.kind == RecordKind.LABEL && it.column != LabelColumn.QUANTITY }.map { it.id }.toSet() }
    LaunchedEffect(query, column) { selected = emptySet(); anchor = null }
    LaunchedEffect(result.records) { selected = selected.intersect(result.records.map { it.id }.toSet()) }
    LaunchedEffect(focusedRow) {
        val index = visible.indexOfFirst { it.id == focusedRow }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    fun copy() { if (selected.isNotEmpty()) actions.copyText(visible.filter { it.id in selected }.joinToString("\n") { it.text }) }
    fun select(record: LabelRecord, ctrl: Boolean, shift: Boolean) {
        selected = if (shift && anchor != null) {
            val start = visible.indexOfFirst { it.id == anchor }
            val end = visible.indexOfFirst { it.id == record.id }
            if (start >= 0 && end >= 0) visible.subList(minOf(start, end), maxOf(start, end) + 1).map { it.id }.toSet() else setOf(record.id)
        } else if (ctrl) { if (record.id in selected) selected - record.id else selected + record.id }
        else setOf(record.id)
        if (!shift) anchor = record.id
        focusedRow = record.id
        if (record.page > 0) onSource(record)
    }
    Column(modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else if (event.isCtrlPressed && event.key == Key.F) { searchFocus.requestFocus(); true }
        else if (queryFocused) false
        else when {
            event.isCtrlPressed && event.key == Key.A -> { selected = visible.map { it.id }.toSet(); true }
            event.isCtrlPressed && event.key == Key.C -> { copy(); true }
            event.isCtrlPressed && event.key == Key.Z && canUndo -> { actions.undo(); true }
            event.key == Key.Delete && deletable.isNotEmpty() -> { deleteOpen = true; true }
            event.key == Key.F2 && selected.size == 1 && deletable.isNotEmpty() -> { editing = selected.first(); true }
            event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                if (visible.isNotEmpty()) {
                    val current = visible.indexOfFirst { it.id == focusedRow }
                    val next = if (event.key == Key.DirectionDown) (current + 1).coerceIn(0, visible.lastIndex) else (current - 1).coerceIn(0, visible.lastIndex)
                    select(visible[next], event.isCtrlPressed, event.isShiftPressed)
                }; true
            }
            else -> false
        }
    }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.documentName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${result.mode.title} · ${desktopDate(result.createdAt)}" + if (!result.completed) " · 未完成" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索标签或柜体 · Ctrl+F") }, singleLine = true,
                leadingIcon = { Symbol("search", modifier = Modifier.size(18.dp)) }, trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Symbol("close", "清空搜索") }
                }, modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onFocusChanged { queryFocused = it.isFocused }, shape = RoundedCornerShape(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = column == null, onClick = { column = null }, label = { Text("全部") }, shape = RoundedCornerShape(8.dp))
                result.mode.columns.forEach { type -> FilterChip(selected = column == type, onClick = { column = type }, label = { Text("${type.letter} ${type.title}") }, shape = RoundedCornerShape(8.dp)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = actions.undo, enabled = canUndo, modifier = Modifier.size(34.dp)) { Symbol("undo", "撤销 · Ctrl+Z") }
                IconButton(onClick = ::copy, enabled = selected.isNotEmpty(), modifier = Modifier.size(34.dp)) { Symbol("content_copy", "复制内容 · Ctrl+C") }
                IconButton(onClick = { editing = selected.singleOrNull() }, enabled = selected.size == 1 && deletable.isNotEmpty(), modifier = Modifier.size(34.dp)) { Symbol("edit", "编辑标签 · F2") }
                IconButton(onClick = { addOpen = true }, enabled = result.mode.columns.any { it != LabelColumn.QUANTITY }, modifier = Modifier.size(34.dp)) { Symbol("add", "手动补充标签") }
                IconButton(onClick = { deleteOpen = true }, enabled = deletable.isNotEmpty(), modifier = Modifier.size(34.dp)) { Symbol("delete", "删除选中标签 · 删除键") }
                Text("${visible.size} 行 · 已选 ${selected.size}", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result.issues.isNotEmpty()) TextButton(onClick = { issuesOpen = !issuesOpen }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("说明 ${result.issues.size}", style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (issuesOpen) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(result.issues) { _, issue ->
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(issue.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        if (issue.page > 0) TextButton(onClick = { onSource(LabelRecord("issue_${issue.page}", result.mode.columns.first(), issue.layout, issue.message, page = issue.page)) }) { Text("${issue.page} 页") }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tableWidth = maxOf(maxWidth, 720.dp)
            Column(Modifier.fillMaxSize().padding(bottom = 10.dp).horizontalScroll(tableHorizontal).focusRequester(tableFocus).focusable()) {
                Row(Modifier.width(tableWidth).height(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 8.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visible.isNotEmpty() && selected.size == visible.size, onCheckedChange = { selected = if (it) visible.map { record -> record.id }.toSet() else emptySet() }, modifier = Modifier.width(40.dp))
                    TableCell("序号", Modifier.width(56.dp), true)
                    TableCell("类型", Modifier.width(96.dp), true)
                    TableCell("柜体", Modifier.width(100.dp), true)
                    TableCell("标签内容", Modifier.weight(1f), true)
                    TableCell("来源页", Modifier.width(76.dp), true)
                }
                Box(Modifier.width(tableWidth).weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
                    if (visible.isEmpty()) item { Text("没有匹配的标签。", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(visible, key = { _, record -> record.id }) { index, record ->
                        val heading = record.kind in setOf(RecordKind.GROUP, RecordKind.FILE_LABEL, RecordKind.UM_HEADER)
                        val color = when {
                            record.id in selected -> MaterialTheme.colorScheme.secondaryContainer
                            heading -> MaterialTheme.colorScheme.surfaceContainerHigh
                            index % 2 == 0 -> MaterialTheme.colorScheme.surface
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(color)
                            .onPointerEvent(PointerEventType.Press) { event -> controlClick = event.keyboardModifiers.isCtrlPressed; shiftClick = event.keyboardModifiers.isShiftPressed }
                            .combinedClickable(onClick = { tableFocus.requestFocus(); select(record, controlClick, shiftClick) },
                                onDoubleClick = { if (record.kind == RecordKind.LABEL && record.column != LabelColumn.QUANTITY) editing = record.id })
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(record.id in selected, { select(record, true, false) }, Modifier.width(40.dp))
                            TableCell((originalOrder[record.id] ?: index + 1).toString(), Modifier.width(56.dp))
                            TableCell("${record.column.letter} ${record.column.title}", Modifier.width(96.dp))
                            TableCell(record.layout.ifBlank { "—" }, Modifier.width(100.dp))
                            Text(record.text, Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp), style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (record.edited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if (record.page > 0) TextButton(onClick = { onSource(record) }, modifier = Modifier.width(76.dp), contentPadding = PaddingValues(2.dp)) { Text(record.page.toString(), style = MaterialTheme.typography.labelMedium) }
                            else TableCell("—", Modifier.width(76.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(tableHorizontal), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        Text("各类型按原始顺序独立排列 · 双击或 F2 编辑 · 按住 Ctrl 或 Shift 多选", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    result.records.find { it.id == editing }?.takeIf { it.kind == RecordKind.LABEL && it.column != LabelColumn.QUANTITY }?.let { record ->
        DesktopLabelEditor(record, onSave = { text ->
            runCatching { LabelEdits.change(result, record.id, text) }.fold(
                onSuccess = { actions.editRecord(record.id, text); editing = null; null },
                onFailure = { UserFacingErrors.describe(it) },
            )
        }, onDismiss = { editing = null })
    }
    if (addOpen) DesktopAddLabel(result, onSave = { type, layout, text ->
        runCatching { LabelEdits.add(result, type, layout, text) }.fold(
            onSuccess = { actions.addRecord(type, layout, text); addOpen = false; null },
            onFailure = { UserFacingErrors.describe(it) },
        )
    }, onDismiss = { addOpen = false })
    if (deleteOpen) AlertDialog(onDismissRequest = { deleteOpen = false }, shape = RoundedCornerShape(12.dp), title = { Text("删除 ${deletable.size} 条标签？") },
        text = { Text("只删除选中的可编辑标签，原始 PDF 不变，数量说明自动重新计算。可在本次会话中撤销。") },
        confirmButton = { TextButton(onClick = { actions.deleteRecords(deletable); selected = emptySet(); deleteOpen = false }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } })
}

@Composable
private fun TableCell(text: String, modifier: Modifier, heading: Boolean = false) {
    Text(text, modifier.padding(horizontal = 6.dp), style = if (heading) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DesktopLabelEditor(record: LabelRecord, onSave: (String) -> String?, onDismiss: () -> Unit) {
    var text by remember(record.id) { mutableStateOf(TextFieldValue(record.text)) }
    var error by remember(record.id) { mutableStateOf<String?>(null) }
    val valid = text.text.isNotBlank() && text.text.length <= 500 && text.text.none { it == '\n' || it == '\r' }
    fun save() { error = onSave(text.text.trim()) }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(12.dp), title = { Text("编辑 ${record.column.title}") },
        text = { Column(Modifier.widthIn(min = 400.dp, max = 600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(record.layout, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(text, { text = it; error = null }, singleLine = true, isError = !valid || error != null, label = { Text("标签内容") },
                supportingText = { Text(error ?: "不能为空，最多 500 字，不含换行。") }, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter && text.composition == null && valid) { save(); true } else false
                }, shape = RoundedCornerShape(8.dp))
            if (record.edited) Text("原始内容：${record.sourceText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, confirmButton = { Button(onClick = ::save, enabled = valid && text.composition == null, shape = RoundedCornerShape(10.dp)) { Text("保存 · Ctrl+回车") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun DesktopAddLabel(result: ExtractionResult, onSave: (LabelColumn, String, String) -> String?, onDismiss: () -> Unit) {
    val columns = result.mode.columns.filter { it != LabelColumn.QUANTITY }
    val layouts = result.records.map { it.layout }.filter(String::isNotBlank).distinct()
    if (columns.isEmpty()) return
    var type by remember { mutableStateOf(columns.first()) }
    var layout by remember { mutableStateOf(layouts.firstOrNull().orEmpty()) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    var typeOpen by remember { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    val valid = text.text.isNotBlank() && text.text.length <= 500 && text.text.none { it == '\n' || it == '\r' } && layout.isNotBlank()
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(12.dp), title = { Text("补充标签") },
        text = { Column(Modifier.widthIn(min = 400.dp, max = 600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box { OutlinedButton(onClick = { typeOpen = true }, shape = RoundedCornerShape(8.dp)) { Text("${type.letter} ${type.title}") }
                    DropdownMenu(typeOpen, { typeOpen = false }) { columns.forEach { value -> DropdownMenuItem(text = { Text(value.title) }, onClick = { type = value; typeOpen = false; error = null }) } } }
                Box { OutlinedButton(onClick = { layoutOpen = true }, enabled = layouts.isNotEmpty(), shape = RoundedCornerShape(8.dp)) { Text(layout.ifBlank { "无可选柜体" }) }
                    DropdownMenu(layoutOpen, { layoutOpen = false }) { layouts.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { layout = value; layoutOpen = false; error = null }) } } }
            }
            OutlinedTextField(text, { text = it; error = null }, singleLine = true, isError = error != null, label = { Text("标签内容") }, supportingText = { Text(error ?: "最多 500 字，不含换行。保存后参与导出和计数。") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp))
        } }, confirmButton = { Button(onClick = { error = onSave(type, layout, text.text.trim()) }, enabled = valid && text.composition == null, shape = RoundedCornerShape(10.dp)) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
