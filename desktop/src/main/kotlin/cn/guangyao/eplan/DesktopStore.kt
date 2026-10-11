package cn.guangyao.eplan

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Only this store writes formal desktop data. JSON is streamed; failed saves keep the old file. */
class DesktopStore(val root: Path) {
    val documentsDirectory: Path = root.resolve("documents")
    val cacheDirectory: Path = root.resolve("cache")
    val defaultExportDirectory: Path = root.resolve("exports")
    private val workspace = root.resolve("workspace.json")
    private val catalog = root.resolve("exports-index.json")
    private val preferences = root.resolve("preferences.json")
    private val blockedWrites = mutableSetOf<Path>()

    data class Loaded(
        val data: PocketData,
        val theme: DesktopThemeMode,
        val exportDirectory: String,
        val message: String?,
    )

    init {
        Files.createDirectories(root)
        Files.createDirectories(documentsDirectory)
        Files.createDirectories(cacheDirectory)
        Files.createDirectories(defaultExportDirectory)
    }

    @Synchronized
    fun load(): Loaded {
        val messages = mutableListOf<String>()
        val data = recover(workspace, PocketData(), messages, "工作区") { reader ->
            var version = -1
            var documents: List<ImportedDocument>? = null
            var results: List<ExtractionResult>? = null
            var rules: Map<ExtractionMode, RuleSettings>? = null
            var exports = emptyList<ExportedFile>()
            reader.fields { key -> when (key) {
                "version" -> version = nextInt()
                "documents" -> documents = array { readDocument() }
                "results" -> results = array { readResult() }
                "rules" -> {
                    val values = linkedMapOf<ExtractionMode, RuleSettings>()
                    fields { mode -> values[ExtractionMode.valueOf(mode)] = readRules() }
                    rules = values
                }
                "exports" -> exports = array { readExport() }
                else -> skipValue()
            } }
            require(version == 1 && documents != null && results != null && rules != null) { "工作区版本或字段无效。" }
            PocketData(documents!!, results!!, rules!!, exports)
        }
        val exports = recover(catalog, data.exports, messages, "文件索引") { reader ->
            var version = -1
            var values: List<ExportedFile>? = null
            reader.fields { key -> when (key) {
                "version" -> version = nextInt()
                "exports" -> values = array { readExport() }
                else -> skipValue()
            } }
            require(version == 1 && values != null) { "文件索引版本或字段无效。" }
            values!!
        }
        val prefs = recover(preferences, DesktopThemeMode.SYSTEM to defaultExportDirectory.toString(), messages, "偏好设置") { reader ->
            var version = -1
            var theme = DesktopThemeMode.SYSTEM
            var directory = defaultExportDirectory.toString()
            reader.fields { key -> when (key) {
                "version" -> version = nextInt()
                "theme" -> theme = DesktopThemeMode.valueOf(nextString())
                "exportDirectory" -> directory = nextString()
                else -> skipValue()
            } }
            require(version == 1 && directory.isNotBlank()) { "偏好设置无效。" }
            theme to Path.of(directory).toAbsolutePath().normalize().toString()
        }
        restoreStagedDocuments(data.documents, messages)
        return Loaded(data.copy(exports = exports), prefs.first, prefs.second, messages.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    @Synchronized
    fun saveWorkspace(data: PocketData) = atomicJson(workspace) {
        beginObject().name("version").value(1)
        name("documents").beginArray(); data.documents.forEach { writeDocument(it) }; endArray()
        name("results").beginArray(); data.results.forEach { writeResult(it) }; endArray()
        name("rules").beginObject(); data.rules.forEach { (mode, rules) -> name(mode.name); writeRules(rules) }; endObject()
        // Export metadata has a separate small authoritative index, avoiding huge rewrites on favorite.
        endObject()
    }

    @Synchronized
    fun saveExports(exports: List<ExportedFile>) = atomicJson(catalog) {
        beginObject().name("version").value(1).name("exports").beginArray()
        exports.forEach { writeExport(it) }
        endArray().endObject()
    }

    @Synchronized
    fun savePreferences(theme: DesktopThemeMode, exportDirectory: String) = atomicJson(preferences) {
        beginObject().name("version").value(1).name("theme").value(theme.name)
            .name("exportDirectory").value(exportDirectory).endObject()
    }

    /** Original filename remains the machine label; a UUID directory prevents source-name collisions. */
    fun importDocument(source: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): ImportedDocument {
        require(source.isFile && source.canRead() && source.name.endsWith(".pdf", true)) { "请选择可读取的 PDF 文件。" }
        val expected = source.length()
        require(expected <= MAX_PDF_BYTES) { "PDF 超过 1000 MiB，请拆分后导入。" }
        val id = UUID.randomUUID().toString()
        val directory = documentsDirectory.resolve(id)
        Files.createDirectory(directory)
        val destination = directory.resolve(DesktopFileNames.safe(source.name, "pdf"))
        val part = directory.resolve("import.part")
        var copied = 0L
        try {
            source.inputStream().buffered(64 * 1024).use { input ->
                val prefix = ByteArray(1024)
                var used = 0
                while (used < prefix.size) {
                    val count = input.read(prefix, used, prefix.size - used)
                    if (count < 0) break
                    used += count
                }
                require(String(prefix, 0, used, Charsets.ISO_8859_1).contains("%PDF-")) { "文件不是有效 PDF。" }
                FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    val output = Channels.newOutputStream(channel)
                    fun write(bytes: ByteArray, count: Int) {
                        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("已取消导入。")
                        require(copied + count <= MAX_PDF_BYTES) { "PDF 超过 1000 MiB，请拆分后导入。" }
                        if (copied % (4L * 1024 * 1024) < count || copied == 0L) {
                            require(Files.getFileStore(directory).usableSpace > RESERVE_BYTES + count) { "磁盘空间不足，请释放空间后重试。" }
                        }
                        output.write(bytes, 0, count)
                        copied += count
                        progress(copied, expected)
                    }
                    write(prefix, used)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        write(buffer, count)
                    }
                    output.flush()
                    channel.force(true)
                }
            }
            if (cancelled()) throw InterruptedIOException("已取消导入。")
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE)
            return ImportedDocument(id, source.name, destination.toAbsolutePath().toString(), copied, System.currentTimeMillis())
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(part) }
            runCatching { Files.deleteIfExists(destination) }
            runCatching { Files.deleteIfExists(directory) }
            throw error
        }
    }

    /** Stage app copies, commit metadata, then discard staged data. Failed commits restore the copies. */
    @Synchronized
    fun removeDocuments(documents: List<ImportedDocument>, next: PocketData): String? {
        val staged = mutableListOf<Pair<Path, Path>>()
        try {
            documents.forEach { document ->
                val original = ownedDocumentDirectory(document)
                if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                    val trash = documentsDirectory.resolve(".trash-" + document.id + "-" + UUID.randomUUID())
                    Files.move(original, trash, StandardCopyOption.ATOMIC_MOVE)
                    staged += original to trash
                }
            }
            saveWorkspace(next)
        } catch (error: Throwable) {
            var failedRestore = false
            staged.asReversed().forEach { (original, trash) ->
                if (runCatching { Files.move(trash, original, StandardCopyOption.ATOMIC_MOVE) }.isFailure) failedRestore = true
            }
            if (failedRestore) throw IOException("保存失败，部分导入副本暂存于 documents/.trash-*；请保留数据目录并重试。", error)
            throw error
        }
        var leftovers = false
        staged.forEach { (_, trash) -> if (runCatching { deleteOwnedTree(trash, documentsDirectory) }.isFailure) leftovers = true }
        documents.forEach { document ->
            val cache = cacheDirectory.resolve(document.id)
            if (runCatching { deleteOwnedTree(cache, cacheDirectory) }.isFailure) leftovers = true
        }
        return if (leftovers) "记录已清理；被占用的暂存副本已保留在数据目录，原文件和导出 Excel 保留。" else null
    }

    fun discardImported(document: ImportedDocument) {
        deleteOwnedTree(ownedDocumentDirectory(document), documentsDirectory)
    }

    private fun restoreStagedDocuments(documents: List<ImportedDocument>, messages: MutableList<String>) {
        documents.forEach { document ->
            runCatching {
                val target = ownedDocumentDirectory(document)
                if (!Files.exists(target)) {
                    val staged = Files.newDirectoryStream(documentsDirectory, ".trash-" + document.id + "-*").use { paths ->
                        paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }.toList()
                    }
                    if (staged.size == 1) {
                        Files.move(staged.single(), target, StandardCopyOption.ATOMIC_MOVE)
                        messages += "已恢复上次中断删除时暂存的图纸副本：" + document.name
                    }
                }
            }.onFailure { messages += "图纸副本恢复未完成，请保留 documents/.trash-*：" + document.name }
        }
    }

    private fun ownedDocumentDirectory(document: ImportedDocument): Path {
        require(runCatching { UUID.fromString(document.id) }.isSuccess) { "图纸副本路径无效，未删除任何文件。" }
        val expected = documentsDirectory.resolve(document.id).toAbsolutePath().normalize()
        val actual = Path.of(document.path).toAbsolutePath().normalize()
        require(actual.parent == expected && !Files.isSymbolicLink(expected) && !Files.isSymbolicLink(actual)) {
            "图纸不在应用副本目录，未删除任何文件。"
        }
        if (Files.exists(expected)) require(expected.toRealPath().parent == documentsDirectory.toRealPath()) { "拒绝删除目录外文件。" }
        return expected
    }

    private fun deleteOwnedTree(path: Path, parent: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        require(path.toAbsolutePath().normalize().parent == parent.toAbsolutePath().normalize() && !Files.isSymbolicLink(path)) { "拒绝清理目录外文件。" }
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { current -> Files.deleteIfExists(current) }
        }
    }

    private fun <T> recover(path: Path, fallback: T, messages: MutableList<String>, label: String, read: (JsonReader) -> T): T {
        if (Files.notExists(path)) return fallback
        if (!Files.exists(path)) {
            blockedWrites.add(path)
            messages += label + "暂时无法读取，已保护原记录并停止覆盖；请检查磁盘或权限后重启。"
            return fallback
        }
        return try {
            Files.newBufferedReader(path, Charsets.UTF_8).use { input ->
                JsonReader(input).use { reader ->
                    reader.setStrictness(Strictness.STRICT)
                    val value = read(reader)
                    require(reader.peek() == JsonToken.END_DOCUMENT) { "JSON 尾部存在额外内容。" }
                    value
                }
            }
        } catch (error: Exception) {
            val backup = path.resolveSibling(path.fileName.toString() + ".recovery-" + System.currentTimeMillis() + "-" + UUID.randomUUID())
            val copied = runCatching { Files.copy(path, backup) }.isSuccess
            if (!copied) blockedWrites.add(path)
            messages += label + "无法读取，" + if (copied) "已保留恢复副本，请查看数据目录。" else "恢复副本无法写入，请保留原文件并检查权限。"
            fallback
        }
    }

    private fun atomicJson(path: Path, write: JsonWriter.() -> Unit) {
        require(path !in blockedWrites) { "原记录无法读取且恢复副本无法保存，为保护原记录，本次保存已停止。请检查数据目录权限后重启。" }
        val temporary = path.resolveSibling("." + path.fileName + "." + UUID.randomUUID() + ".part")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                JsonWriter(OutputStreamWriter(Channels.newOutputStream(channel), Charsets.UTF_8)).use { writer ->
                    writer.write()
                    writer.flush()
                    channel.force(true)
                }
            }
            // Never downgrade to a non-atomic overwrite: unsupported destinations fail and retain old data.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val MAX_PDF_BYTES: Long = 1000L * 1024 * 1024
        private const val RESERVE_BYTES: Long = 64L * 1024 * 1024
    }

    private fun JsonWriter.writeExport(v: ExportedFile) {
        beginObject().name("id").value(v.id).name("resultId").value(v.resultId)
            .name("documentName").value(v.documentName).name("mode").value(v.mode.name)
            .name("name").value(v.name).name("uri").value(v.uri).name("createdAt").value(v.createdAt)
            .name("bytes").value(v.bytes).name("favorite").value(v.favorite)
            .name("completed").value(v.completed).name("displayName").value(v.displayName)
            .name("storageKey").value(v.storageKey).endObject()
    }
    private fun JsonReader.readExport(): ExportedFile {
        var id = ""; var resultId = ""; var documentName = ""; var mode = ExtractionMode.FULL
        var name = ""; var uri = ""; var createdAt = 0L; var bytes = 0L
        var favorite = false; var completed = true; var displayName: String? = null; var storageKey = ""
        fields { when (it) {
            "id" -> id = nextString(); "resultId" -> resultId = nextString(); "documentName" -> documentName = nextString()
            "mode" -> mode = ExtractionMode.valueOf(nextString()); "name" -> name = nextString(); "uri" -> uri = nextString()
            "createdAt" -> createdAt = nextLong(); "bytes" -> bytes = nextLong(); "favorite" -> favorite = nextBoolean()
            "completed" -> completed = nextBoolean(); "displayName" -> displayName = nextString()
            "storageKey" -> storageKey = nextString(); else -> skipValue()
        } }
        require(id.isNotBlank() && name.isNotBlank() && uri.isNotBlank())
        return ExportedFile(id, resultId, documentName, mode, name, uri, createdAt, bytes.coerceAtLeast(0),
            favorite, completed, displayName?.takeIf { it.isNotBlank() } ?: name, storageKey)
    }

    private fun JsonWriter.writeDocument(v: ImportedDocument) {
        beginObject().name("id").value(v.id).name("name").value(v.name).name("path").value(v.path)
            .name("bytes").value(v.bytes).name("importedAt").value(v.importedAt).name("pageCount").value(v.pageCount).endObject()
    }
    private fun JsonReader.readDocument(): ImportedDocument {
        var id = ""; var name = ""; var path = ""; var bytes = 0L; var importedAt = 0L; var pageCount = 0
        fields { when (it) { "id" -> id = nextString(); "name" -> name = nextString(); "path" -> path = nextString()
            "bytes" -> bytes = nextLong(); "importedAt" -> importedAt = nextLong(); "pageCount" -> pageCount = nextInt(); else -> skipValue() } }
        require(id.isNotBlank() && path.isNotBlank())
        return ImportedDocument(id, name, path, bytes, importedAt, pageCount)
    }
    private fun JsonWriter.writeRules(v: RuleSettings) {
        beginObject().name("cabinetFilter").value(v.cabinetFilter).name("excludedCabinets").value(v.excludedCabinets)
            .name("rotationOverrides").value(v.rotationOverrides).name("functionOverrides").value(v.functionOverrides)
            .name("defaultCn1Cores").value(v.defaultCn1Cores).name("addSpares").value(v.addSpares).endObject()
    }
    private fun JsonReader.readRules(): RuleSettings {
        var included = ""; var excluded = ""; var rotations = ""; var functions = ""; var cores = 6; var spares = true
        fields { when (it) { "cabinetFilter" -> included = nextString(); "excludedCabinets" -> excluded = nextString()
            "rotationOverrides" -> rotations = nextString(); "functionOverrides" -> functions = nextString()
            "defaultCn1Cores" -> cores = nextInt(); "addSpares" -> spares = nextBoolean(); else -> skipValue() } }
        // Legacy machineShortName keys are intentionally ignored. The PDF filename is authoritative.
        return RuleSettings(included, excluded, rotations, functions, cores, spares)
    }
    private fun JsonWriter.writeRecord(v: LabelRecord, documentName: String) {
        val text = if (v.kind == RecordKind.FILE_LABEL) DocumentNames.machineName(documentName) else v.text
        beginObject().name("id").value(v.id).name("column").value(v.column.name).name("layout").value(v.layout)
            .name("text").value(text).name("page").value(v.page).name("x").value(v.x).name("y").value(v.y)
            .name("width").value(v.width).name("height").value(v.height).name("kind").value(v.kind.name)
            .name("sourceText").value(if (v.kind == RecordKind.FILE_LABEL) text else v.sourceText)
            .name("favorite").value(v.favorite).name("edited").value(v.edited).name("cn1Capacity")
        v.cn1Capacity?.let { value(it) } ?: nullValue()
        name("installationId").value(v.installationId).name("deviceId").value(v.deviceId).name("evidence").value(v.evidence)
        endObject()
    }
    private fun JsonReader.readRecord(): LabelRecord {
        var id = ""; var column = LabelColumn.DEVICE; var layout = ""; var text = ""; var page = 0
        var x = 0f; var y = 0f; var width = 0f; var height = 0f; var kind = RecordKind.LABEL
        var sourceText: String? = null; var favorite = false; var edited = false; var capacity: Int? = null
        var installationId = ""; var deviceId = ""; var evidence = ""
        fields { when (it) { "id" -> id = nextString(); "column" -> column = LabelColumn.valueOf(nextString())
            "layout" -> layout = nextString(); "text" -> text = nextString(); "page" -> page = nextInt()
            "x" -> x = nextDouble().toFloat(); "y" -> y = nextDouble().toFloat()
            "width" -> width = nextDouble().toFloat(); "height" -> height = nextDouble().toFloat()
            "kind" -> kind = RecordKind.valueOf(nextString()); "sourceText" -> sourceText = nextString()
            "favorite" -> favorite = nextBoolean(); "edited" -> edited = nextBoolean()
            "cn1Capacity" -> if (peek() == JsonToken.NULL) nextNull() else capacity = nextInt().takeIf { value -> value > 0 }
            "installationId" -> installationId = nextString(); "deviceId" -> deviceId = nextString(); "evidence" -> evidence = nextString()
            else -> skipValue() } }
        require(id.isNotBlank())
        return LabelRecord(id, column, layout, text, page, x, y, width, height, kind, sourceText ?: text, favorite, edited, capacity,
            installationId, deviceId, evidence)
    }
    private fun JsonWriter.writeResult(v: ExtractionResult) {
        beginObject().name("id").value(v.id).name("documentId").value(v.documentId).name("documentName").value(v.documentName)
            .name("mode").value(v.mode.name).name("createdAt").value(v.createdAt).name("pageCount").value(v.pageCount)
            .name("completed").value(v.completed).name("rules")
        writeRules(v.rules)
        name("records").beginArray(); v.records.forEach { writeRecord(it, v.documentName) }; endArray()
        name("issues").beginArray(); v.issues.forEach {
            beginObject().name("message").value(it.message).name("page").value(it.page).name("layout").value(it.layout).endObject()
        }; endArray().endObject()
    }
    private fun JsonReader.readResult(): ExtractionResult {
        var id = ""; var documentId = ""; var name = ""; var mode = ExtractionMode.FULL; var createdAt = 0L; var pageCount = 0
        var completed = true; var rules = RuleSettings(); var records = emptyList<LabelRecord>(); var issues = emptyList<ExtractionIssue>()
        fields { when (it) { "id" -> id = nextString(); "documentId" -> documentId = nextString(); "documentName" -> name = nextString()
            "mode" -> mode = ExtractionMode.valueOf(nextString()); "createdAt" -> createdAt = nextLong(); "pageCount" -> pageCount = nextInt()
            "completed" -> completed = nextBoolean(); "rules" -> rules = readRules(); "records" -> records = array { readRecord() }
            "issues" -> issues = array {
                var message = ""; var page = 0; var layout = ""
                fields { key -> when(key) { "message" -> message = nextString(); "page" -> page = nextInt(); "layout" -> layout = nextString(); else -> skipValue() } }
                ExtractionIssue(message, page, layout)
            }; else -> skipValue() } }
        require(id.isNotBlank() && documentId.isNotBlank())
        records = records.map { if (it.kind == RecordKind.FILE_LABEL) it.copy(text = DocumentNames.machineName(name), sourceText = DocumentNames.machineName(name)) else it }
        return ExtractionResult(id, documentId, name, mode, createdAt, records, issues, pageCount, completed, rules)
    }
    private fun JsonReader.fields(action: JsonReader.(String) -> Unit) {
        beginObject(); while (hasNext()) action(nextName()); endObject()
    }
    private fun <T> JsonReader.array(read: JsonReader.() -> T): List<T> {
        val values = ArrayList<T>()
        beginArray(); while (hasNext()) values.add(read()); endArray()
        return values
    }
}


/** Valid on Windows even when a PDF originally came from another operating system. */
internal object DesktopFileNames {
    fun safe(value: String, extension: String): String {
        val suffix = "." + extension
        var base = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("(?i)\\." + Regex.escape(extension) + "$"), "")
            .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
            .trim().trimEnd('.', ' ')
        if (base.isBlank()) base = "未命名"
        if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(base)) base = "_" + base
        base = base.take(120).trimEnd('.', ' ')
        if (base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
        return base + suffix
    }
}

