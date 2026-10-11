package cn.guangyao.eplan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopRuleEditor(mode: ExtractionMode, initial: RuleSettings, documentName: String?, onSave: (RuleSettings) -> Unit, onDismiss: () -> Unit) {
    var included by remember(mode, initial) { mutableStateOf(initial.cabinetFilter) }
    var excluded by remember(mode, initial) { mutableStateOf(initial.excludedCabinets) }
    var rotations by remember(mode, initial) { mutableStateOf(initial.rotationOverrides) }
    var functions by remember(mode, initial) { mutableStateOf(initial.functionOverrides) }
    var cores by remember(mode, initial) { mutableStateOf(initial.defaultCn1Cores.toString()) }
    var spare by remember(mode, initial) { mutableStateOf(initial.addSpares) }
    var discard by remember { mutableStateOf(false) }
    val candidate = RuleSettings(included.trim(), excluded.trim(), rotations.trim(), functions.trim(), cores.toIntOrNull() ?: 0, spare)
    val rotationError = desktopRotationError(rotations)
    val functionError = desktopFunctionError(functions)
    val hasCn1 = mode in listOf(ExtractionMode.FULL, ExtractionMode.CN1, ExtractionMode.QUANTITY)
    val hasFunctions = mode in listOf(ExtractionMode.FULL, ExtractionMode.FUNCTION, ExtractionMode.QUANTITY)
    val hasRotations = mode != ExtractionMode.CN1
    val valid = (!hasRotations || rotationError == null) && (!hasFunctions || functionError == null) && (!hasCn1 || candidate.defaultCn1Cores in 1..100)
    fun close() { if (candidate != initial) discard = true else onDismiss() }
    AlertDialog(onDismissRequest = ::close, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(min = 480.dp, max = 720.dp),
        title = { Text("${mode.title}规则", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("这份设置只用于${mode.title}，保存后下次提取生效。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("机台名称使用完整 PDF 文件名", style = MaterialTheme.typography.titleSmall)
                        Text(documentName ?: "导入后自动读取，保留 .pdf 扩展名", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DesktopRuleField(included, { included = it }, "包含柜体", "留空表示全部；多个完整编号用逗号或换行分隔。")
                DesktopRuleField(excluded, { excluded = it }, "排除柜体", "排除设置优先于包含设置。")
                if (hasRotations) DesktopRuleField(rotations, { rotations = it }, "方向修正（可选）", "每行柜体=角度，例如 C01=90；单页可用 page:259=90。支持 0、90、180、270。", rotationError)
                if (hasFunctions) DesktopRuleField(functions, { functions = it }, "设备功能修正（可选）", "每行柜体/设备=功能；多条独立信号用 | 分隔，只修正布局已有器件。", functionError)
                if (hasCn1) {
                    OutlinedTextField(cores, { cores = it }, label = { Text("每轴默认 CN1 容量") }, singleLine = true,
                        isError = candidate.defaultCn1Cores !in 1..100, supportingText = { Text("请输入 1–100，图纸未明确容量时使用。") },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("按容量补齐备用")
                        Switch(spare, { spare = it })
                    }
                }
                Text("按真实安装位置提取，保留重复与安装行顺序。功能不从原理图或 BOM 补造；仅指定五类模块缺少功能时补名，仅 FU 加所属安数。提取自动保存，导出无需审核。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(candidate) }, enabled = valid, shape = RoundedCornerShape(10.dp)) { Text("保存规则") } },
        dismissButton = { TextButton(onClick = ::close) { Text("取消") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, shape = RoundedCornerShape(12.dp),
        title = { Text("放弃未保存的规则？") }, text = { Text("此前保存的规则会保留。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable
private fun DesktopRuleField(value: String, change: (String) -> Unit, title: String, help: String, error: String? = null) {
    OutlinedTextField(value, change, label = { Text(title) }, supportingText = { Text(error ?: help) }, isError = error != null,
        minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp))
}

private fun desktopRotationError(value: String): String? {
    val keys = mutableSetOf<String>()
    value.lineSequence().forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isNotEmpty()) {
            val match = Regex("^([^=\\s]+)\\s*=\\s*(0|90|180|270)$").matchEntire(line) ?: return "第 ${index + 1} 行应为柜体=角度"
            val key = match.groupValues[1].uppercase()
            if (key.startsWith("PAGE:") && (key.substringAfter(':').toIntOrNull() ?: 0) < 1) return "第 ${index + 1} 行页码应从 1 开始"
            if (!keys.add(key)) return "第 ${index + 1} 行重复设置 $key"
        }
    }
    return null
}

private fun desktopFunctionError(value: String): String? {
    val keys = mutableSetOf<String>()
    value.lineSequence().forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isNotEmpty()) {
            val match = Regex("^([^/=\\s]+/[^/=\\s]+)\\s*=\\s*(.+)$").matchEntire(line) ?: return "第 ${index + 1} 行应为柜体/设备=功能"
            if (match.groupValues[2].split('|').any(String::isBlank)) return "第 ${index + 1} 行有空功能"
            if (!keys.add(match.groupValues[1].uppercase())) return "第 ${index + 1} 行重复设置设备"
        }
    }
    return null
}
