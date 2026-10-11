package cn.guangyao.eplan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopFilesPage(state: DesktopState, actions: DesktopActions, modifier: Modifier, confirm: (String, () -> Unit) -> Unit) {
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<ExtractionMode?>(null) }
    var renaming by remember { mutableStateOf<ExportedFile?>(null) }
    var displayName by remember { mutableStateOf("") }
    val files = remember(state.data.exports, query, favoritesOnly, mode) {
        state.data.exports.filter { file -> (!favoritesOnly || file.favorite) && (mode == null || file.mode == mode) &&
            (query.isBlank() || file.displayName.contains(query.trim(), true) || file.documentName.contains(query.trim(), true)) }.sortedByDescending { it.createdAt }
    }
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("导出文件", style = MaterialTheme.typography.headlineSmall)
                Text("在工作台直接导出 Excel，已生成文件会保存在这里。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = actions.chooseExportDirectory, shape = RoundedCornerShape(10.dp)) { Text("更改目录") }
            OutlinedButton(onClick = actions.openExportFolder, shape = RoundedCornerShape(10.dp)) { Text("打开文件夹") }
            IconButton(onClick = actions.refreshExports) { Symbol("sync_alt", "刷新文件") }
        }
        Text(state.exportDirectory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索文件名或来源图纸") }, leadingIcon = { Symbol("search") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !favoritesOnly, onClick = { favoritesOnly = false }, label = { Text("全部文件") }, shape = RoundedCornerShape(8.dp))
            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("收藏") }, leadingIcon = { Symbol("favorite", modifier = Modifier.size(16.dp), filled = favoritesOnly) }, shape = RoundedCornerShape(8.dp))
            FilterChip(selected = mode == null, onClick = { mode = null }, label = { Text("全部类型") }, shape = RoundedCornerShape(8.dp))
            ExtractionMode.entries.forEach { type -> FilterChip(selected = mode == type, onClick = { mode = type }, label = { Text(type.title) }, shape = RoundedCornerShape(8.dp)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol("folder_open", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.data.exports.isEmpty()) "还没有导出文件" else "没有符合筛选条件的文件", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.data.exports.isEmpty()) "提取完成后，在工作台点击“导出 Excel”。" else "试试其他关键词、类型或收藏条件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.data.exports.isNotEmpty()) OutlinedButton(onClick = { query = ""; mode = null; favoritesOnly = false }, shape = RoundedCornerShape(10.dp)) { Text("清除筛选") }
                }
            }
            items(files, key = { it.id }) { file ->
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol("description", modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(file.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${file.mode.title} · ${desktopDate(file.createdAt)} · ${(file.bytes / 1024).coerceAtLeast(1)} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(file.documentName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { actions.favoriteExport(file.id) }) { Symbol("favorite", if (file.favorite) "取消收藏" else "收藏文件", filled = file.favorite) }
                        IconButton(onClick = { renaming = file; displayName = file.displayName }) { Symbol("edit", "修改显示名称") }
                        OutlinedButton(onClick = { actions.openFile(file.uri) }, shape = RoundedCornerShape(10.dp)) { Text("打开") }
                        IconButton(onClick = { confirm("删除设备中的 ${file.name}，同时移除文件记录与收藏。此操作不能撤销。") { actions.deleteExport(file.id) } }) { Symbol("delete", "删除文件") }
                    }
                }
            }
        }
    }
    renaming?.let { file -> AlertDialog(onDismissRequest = { renaming = null }, shape = RoundedCornerShape(12.dp), title = { Text("修改显示名称") },
        text = { OutlinedTextField(displayName, { displayName = it }, singleLine = true, label = { Text("显示名称") }, supportingText = { Text("用于应用内显示，最多 180 字。") }, shape = RoundedCornerShape(8.dp)) },
        confirmButton = { Button(onClick = { actions.renameExport(file.id, displayName.trim()); renaming = null }, enabled = displayName.isNotBlank() && displayName.length <= 180, shape = RoundedCornerShape(10.dp)) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }) }
}

@Composable
internal fun DesktopSettingsPage(state: DesktopState, actions: DesktopActions, modifier: Modifier) {
    var section by remember { mutableStateOf("外观") }
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.width(200.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设置", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
            listOf("外观" to "person", "关于" to "info", "离线工具" to "shield").forEach { (title, icon) ->
                Surface(color = if (section == title) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable { section = title }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(icon, modifier = Modifier.size(20.dp), filled = section == title); Text(title)
                    }
                }
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(section, style = MaterialTheme.typography.headlineSmall)
            when (section) {
                "外观" -> {
                    Text("Purple 浅深主题，偏好保存在这台电脑。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DesktopThemeMode.entries.forEach { theme ->
                        val title = when (theme) { DesktopThemeMode.SYSTEM -> "跟随系统"; DesktopThemeMode.LIGHT -> "浅色模式"; DesktopThemeMode.DARK -> "深色模式" }
                        Surface(color = if (state.theme == theme) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { actions.setTheme(theme) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioButton(selected = state.theme == theme, onClick = { actions.setTheme(theme) })
                                Text(title, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Text("为键盘与鼠标安排更宽的工作空间，同时保留熟悉的配色。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "关于" -> {
                    DesktopInfoCard("EPLAN 标签助手", "Windows 离线桌面版。把手抄标签交给程序，把准点下班还给自己；偶尔和 Bug 加个班。")
                    DesktopInfoCard("完整与单项提取", "读取含原生文字的 EPLAN PDF，按实际安装位置与行序生成标签。A–F 是各自独立的打印列表。提取自动保存，点击导出直接生成 Excel。扫描件与 AI 识别暂未接入。")
                    DesktopInfoCard("资料留在本机", "图纸、结果、规则和文件收藏保存在当前电脑。手机与电脑目前各自保存数据，共用提取规则不等于自动同步资料。")
                    OutlinedButton(onClick = actions.openDataFolder, shape = RoundedCornerShape(10.dp)) { Text("打开本机数据目录") }
                }
                else -> {
                    Text("检查本机文件和导出目录，可复制本次检查报告。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { actions.runDiagnostics?.invoke() }, enabled = actions.runDiagnostics != null && !state.progress.running, shape = RoundedCornerShape(10.dp)) { Text("检查本地文件") }
                        OutlinedButton(onClick = actions.openDataFolder, shape = RoundedCornerShape(10.dp)) { Text("打开数据目录") }
                        if (state.helperReport != null) TextButton(onClick = { actions.copyText(state.helperReport) }) { Text("复制报告") }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
                        SelectionContainer { Text(state.helperReport ?: "尚未生成检查报告。此工具不会阻止提取或导出，也不会上传图纸。", Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopInfoCard(title: String, message: String) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
