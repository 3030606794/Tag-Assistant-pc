@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cn.guangyao.eplan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Platform actions are outside the content so the same production UI can be rendered in CI. */
internal data class DesktopActions(
    val importPdf: () -> Unit = {},
    val selectDocument: (String) -> Unit = {},
    val selectResult: (String) -> Unit = {},
    val extract: (ExtractionMode) -> Unit = {},
    val cancel: () -> Unit = {},
    val saveRules: (ExtractionMode, RuleSettings) -> Unit = { _, _ -> },
    val editRecord: (String, String) -> Unit = { _, _ -> },
    val addRecord: (LabelColumn, String, String) -> Unit = { _, _, _ -> },
    val deleteRecords: (Set<String>) -> Unit = {},
    val undo: () -> Unit = {},
    val exportCurrent: () -> Unit = {},
    val deleteDocument: (String) -> Unit = {},
    val deleteResult: (String) -> Unit = {},
    val clearDocuments: () -> Unit = {},
    val favoriteExport: (String) -> Unit = {},
    val deleteExport: (String) -> Unit = {},
    val renameExport: (String, String) -> Unit = { _, _ -> },
    val refreshExports: () -> Unit = {},
    val chooseExportDirectory: () -> Unit = {},
    val setTheme: (DesktopThemeMode) -> Unit = {},
    val clearMessage: () -> Unit = {},
    val openFile: (String) -> Unit = {},
    val openDataFolder: () -> Unit = {},
    val openExportFolder: () -> Unit = {},
    val copyText: (String) -> Unit = {},
    val runDiagnostics: (() -> Unit)? = null,
)

@Composable
fun DesktopApp(controller: DesktopController) {
    val state by controller.state.collectAsState()
    var localMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.message) { if (state.message != null) localMessage = null }
    val actions = remember(controller) {
        DesktopActions(
            importPdf = { chooseDesktopFiles(false) { controller.importFiles(it) } },
            selectDocument = controller::selectDocument, selectResult = controller::selectResult,
            extract = { controller.extract(it) }, cancel = controller::cancel, saveRules = controller::saveRules,
            editRecord = controller::editRecord, addRecord = controller::addRecord, deleteRecords = controller::deleteRecords,
            undo = controller::undo, exportCurrent = controller::exportCurrent,
            deleteDocument = controller::deleteDocument, deleteResult = controller::deleteResult, clearDocuments = controller::clearDocuments,
            favoriteExport = controller::favoriteExport, deleteExport = controller::deleteExport, renameExport = controller::renameExport,
            refreshExports = controller::refreshExports,
            chooseExportDirectory = { chooseDesktopFiles(true) { files -> files.firstOrNull()?.let { controller.setExportDirectory(it.absolutePath) } } },
            setTheme = controller::setTheme,
            clearMessage = { localMessage = null; controller.clearMessage() },
            openFile = controller::openFile, openDataFolder = controller::openDataFolder, openExportFolder = controller::openExportFolder,
            runDiagnostics = controller::runDiagnostics,
            copyText = { text ->
                try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null); localMessage = "已复制标签内容。" }
                catch (_: Exception) { localMessage = "剪贴板暂时不可用，请重试。" }
            },
        )
    }
    DesktopContent(if (state.message == null && localMessage != null) state.copy(message = localMessage) else state, actions)
}

private fun chooseDesktopFiles(directory: Boolean, selected: (List<File>) -> Unit) {
    SwingUtilities.invokeLater {
        val chooser = JFileChooser().apply {
            dialogTitle = if (directory) "选择 Excel 导出目录" else "导入电气图纸 PDF"
            fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = !directory
            if (!directory) { fileFilter = FileNameExtensionFilter("PDF 图纸 (*.pdf)", "pdf"); isAcceptAllFileFilterUsed = false }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val files = if (directory) listOf(chooser.selectedFile) else chooser.selectedFiles.toList().ifEmpty { listOf(chooser.selectedFile) }
            selected(files.filterNotNull())
        }
    }
}

private enum class DesktopPage(val title: String, val icon: String) {
    WORKSPACE("工作台", "view_column"), FILES("文件", "folder_open"), SETTINGS("设置", "settings")
}

@Composable
internal fun DesktopContent(state: DesktopState, actions: DesktopActions, initialPage: String = "workspace", initialSourceRecordId: String? = null) {
    var page by remember { mutableStateOf(DesktopPage.entries.firstOrNull { it.name.equals(initialPage, true) } ?: DesktopPage.WORKSPACE) }
    var mode by remember { mutableStateOf(ExtractionMode.FULL) }
    var modeMenu by remember { mutableStateOf(false) }
    var rulesOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(true) }
    var sourceRecordId by remember(state.selectedResultId) { mutableStateOf(initialSourceRecordId) }
    var issueSource by remember(state.selectedResultId) { mutableStateOf<LabelRecord?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val result = state.selectedResult
    val sourceRecord = result?.records?.firstOrNull { it.id == sourceRecordId } ?: issueSource
    val document = result?.let { item -> state.data.documents.find { it.id == item.documentId } } ?: state.selectedDocument
    val busy = state.progress.running
    Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.O && !busy) { actions.importPdf(); true }
        else if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.E && result != null) { actions.exportCurrent(); true }
        else false
    }, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol("label", modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.width(202.dp)) {
                    Text("EPLAN 标签助手", style = MaterialTheme.typography.titleMedium)
                    Text("离线桌面工作台", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DesktopPage.entries.forEach { destination ->
                    FilledTonalButton(onClick = { page = destination; if (destination == DesktopPage.FILES) actions.refreshExports() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (page == destination) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Symbol(destination.icon, modifier = Modifier.size(18.dp), filled = page == destination)
                        Spacer(Modifier.width(6.dp)); Text(destination.title)
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = actions.importPdf, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                    Symbol("description", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导入 PDF")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (page) {
                DesktopPage.WORKSPACE -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    DocumentSidebar(state, actions, Modifier.width(232.dp).fillMaxHeight()) { message, action -> confirmDelete = message to action }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                OutlinedButton(onClick = { modeMenu = true }, enabled = !busy, shape = RoundedCornerShape(8.dp)) { Text(mode.title); Spacer(Modifier.width(4.dp)); Symbol("chevron_right", modifier = Modifier.size(16.dp)) }
                                DropdownMenu(modeMenu, { modeMenu = false }) {
                                    ExtractionMode.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { mode = item; modeMenu = false }) }
                                }
                            }
                            Button(onClick = { actions.extract(mode) }, enabled = !busy && state.selectedDocument != null, shape = RoundedCornerShape(10.dp)) {
                                Symbol("play_arrow", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("提取")
                            }
                            IconButton(onClick = { rulesOpen = true }, enabled = !busy) { Symbol("tune", "编辑当前提取规则") }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { previewOpen = !previewOpen }) { Symbol("image", if (previewOpen) "收起 PDF 预览" else "展开 PDF 预览") }
                            Button(onClick = actions.exportCurrent, enabled = result != null && result.records.any { it.kind in setOf(RecordKind.LABEL, RecordKind.SPARE) }, shape = RoundedCornerShape(10.dp)) {
                                Symbol("download", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("导出 Excel")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val showPdf = previewOpen && maxWidth >= 760.dp
                            Row(Modifier.fillMaxSize()) {
                                DesktopResultsTable(result, state.canUndo, actions,
                                    onSource = { sourceRecordId = it.id; issueSource = it.takeIf { item -> item.id.startsWith("issue_") }; previewOpen = true }, modifier = Modifier.weight(1f).fillMaxHeight())
                                if (showPdf) {
                                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    DesktopPdfPreview(document, sourceRecord, onOpenExternal = { document?.let { actions.openFile(it.path) } },
                                        onClose = { previewOpen = false }, modifier = Modifier.width((maxWidth * .40f).coerceIn(320.dp, 460.dp)).fillMaxHeight())
                                }
                            }
                            if (previewOpen && !showPdf && document != null) {
                                // Compact windows use a separate preview window so the editable table remains usable.
                                androidx.compose.ui.window.DialogWindow(onCloseRequest = { previewOpen = false }, title = "图纸预览",
                                    state = androidx.compose.ui.window.rememberDialogState(width = 720.dp, height = 680.dp)) {
                                    DesktopPdfPreview(document, sourceRecord, { actions.openFile(document.path) }, { previewOpen = false }, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
                DesktopPage.FILES -> DesktopFilesPage(state, actions, Modifier.weight(1f)) { message, action -> confirmDelete = message to action }
                DesktopPage.SETTINGS -> DesktopSettingsPage(state, actions, Modifier.weight(1f))
            }
            state.message?.let { message ->
                Surface(color = MaterialTheme.colorScheme.inverseSurface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = actions.clearMessage) { Symbol("close", "关闭提示", tint = MaterialTheme.colorScheme.inverseOnSurface) }
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) CircularProgressIndicator(progress = { state.progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Symbol("shield", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (!state.loaded) "正在读取本机工作区…" else state.progress.message.ifBlank { "就绪 · 图纸和标签保存在本机" },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (busy) {
                        Text("${(state.progress.fraction.coerceIn(0f, 1f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = actions.cancel) { Text("取消任务") }
                    }
                    Text("${state.data.documents.size} 份图纸 · ${state.data.results.size} 份结果", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (rulesOpen) DesktopRuleEditor(mode, state.data.rules[mode] ?: RuleSettings(), state.selectedDocument?.name,
        onSave = { actions.saveRules(mode, it); rulesOpen = false }, onDismiss = { rulesOpen = false })
    confirmDelete?.let { (message, action) ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, shape = RoundedCornerShape(12.dp), title = { Text("确认删除？") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; action() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } })
    }
}

@Composable
private fun DocumentSidebar(state: DesktopState, actions: DesktopActions, modifier: Modifier, confirm: (String, () -> Unit) -> Unit) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("图纸", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { confirm("清空应用内所有图纸副本、相关结果和缓存。原始 PDF、已导出 Excel 与规则保留。", actions.clearDocuments) },
                enabled = state.data.documents.isNotEmpty() && !state.progress.running, modifier = Modifier.size(32.dp)) { Symbol("delete", "清空图纸") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.data.documents.isEmpty()) item { Text("尚无图纸\n导入 PDF 开始提取。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.data.documents, key = { it.id }) { document ->
                Surface(shape = RoundedCornerShape(8.dp), color = if (document.id == state.selectedDocumentId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().clickable { actions.selectDocument(document.id) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol("description", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(document.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (document.pageCount > 0) "${document.pageCount} 页" else "已导入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirm("删除 ${document.name} 的应用副本、结果与缓存。原始 PDF 和已导出 Excel 保留。") { actions.deleteDocument(document.id) } },
                            enabled = !state.progress.running, modifier = Modifier.size(28.dp)) { Symbol("delete", "删除图纸 ${document.name}", modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("结果历史", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall)
        val history = state.data.results.filter { state.selectedDocumentId == null || it.documentId == state.selectedDocumentId }.sortedByDescending { it.createdAt }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (history.isEmpty()) item { Text("提取完成后自动保存在这里。", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(history, key = { it.id }) { result ->
                Surface(shape = RoundedCornerShape(8.dp), color = if (result.id == state.selectedResultId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().clickable { actions.selectResult(result.id) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(result.mode.title + if (!result.completed) " · 未完成" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(desktopDate(result.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirm("删除这份${result.mode.title}结果，PDF 与导出文件保留。") { actions.deleteResult(result.id) } }, modifier = Modifier.size(28.dp)) { Symbol("delete", "删除结果", modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}

internal fun desktopDate(time: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(time))
