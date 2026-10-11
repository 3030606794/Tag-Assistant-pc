package cn.guangyao.eplan

enum class LabelColumn(val title: String, val letter: String) {
    DEVICE("三列", "A"), FUNCTION("单列", "B"), YELLOW("黄标", "C"),
    CN1("CN1号码", "D"), FIXTURE("固定座", "E"), QUANTITY("数量说明", "F")
}

enum class ExtractionMode(val title: String, val description: String, val columns: List<LabelColumn>) {
    FULL("完整提取", "一次提取 A–F 六列，生成完整标签表", LabelColumn.entries.toList()),
    DEVICE("三列提取", "A · 按实际安装位置提取设备编号", listOf(LabelColumn.DEVICE)),
    FUNCTION("单列提取", "B · 提取对应设备的功能文字", listOf(LabelColumn.FUNCTION)),
    YELLOW("黄标提取", "C · 提取布局中的 UM 设备标签", listOf(LabelColumn.YELLOW)),
    CN1("CN1号码提取", "D · 提取完整双端线号并计算备用", listOf(LabelColumn.CN1)),
    FIXTURE("固定座", "E · 提取每个布局内不重复的 XT 编号", listOf(LabelColumn.FIXTURE)),
    QUANTITY("数量说明", "F · 读取 A/B/D 计算依据，仅导出数量说明", listOf(LabelColumn.QUANTITY))
}

data class RuleSettings(
    val cabinetFilter: String = "",
    val excludedCabinets: String = "",
    val rotationOverrides: String = "",
    val functionOverrides: String = "",
    val defaultCn1Cores: Int = 6,
    val addSpares: Boolean = true,
)

data class ImportedDocument(
    val id: String,
    val name: String,
    val path: String,
    val bytes: Long,
    val importedAt: Long,
    val pageCount: Int = 0,
)

enum class RecordKind { LABEL, GROUP, FILE_LABEL, UM_HEADER, SPARE }

data class LabelRecord(
    val id: String,
    val column: LabelColumn,
    val layout: String,
    val text: String,
    val page: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val kind: RecordKind = RecordKind.LABEL,
    val sourceText: String = text,
    val favorite: Boolean = false,
    val edited: Boolean = false,
    val cn1Capacity: Int? = null,
    // A/B records share the physical occurrence, never only the printed device identifier.
    val installationId: String = "",
    val deviceId: String = "",
    val evidence: String = "",
)

data class ExtractionIssue(val message: String, val page: Int = 0, val layout: String = "")

data class ExtractionResult(
    val id: String,
    val documentId: String,
    val documentName: String,
    val mode: ExtractionMode,
    val createdAt: Long,
    val records: List<LabelRecord>,
    val issues: List<ExtractionIssue>,
    val pageCount: Int,
    val completed: Boolean = true,
    val rules: RuleSettings = RuleSettings(),
)

data class JobProgress(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val message: String = "",
    val page: Int = 0,
    val totalPages: Int = 0,
)

/** A saved XLSX snapshot remains independent of subsequent edits or deletion of its source result. */
data class ExportedFile(
    val id: String,
    val resultId: String,
    val documentName: String,
    val mode: ExtractionMode,
    val name: String,
    val uri: String,
    val createdAt: Long,
    val bytes: Long = 0,
    val favorite: Boolean = false,
    val completed: Boolean = true,
    val displayName: String = name,
    val storageKey: String = "",
)

data class PocketData(
    val documents: List<ImportedDocument> = emptyList(),
    val results: List<ExtractionResult> = emptyList(),
    val rules: Map<ExtractionMode, RuleSettings> = emptyMap(),
    val exports: List<ExportedFile> = emptyList(),
)
