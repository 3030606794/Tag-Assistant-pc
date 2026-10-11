package cn.guangyao.eplan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.FileChannel
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DesktopThemeMode { SYSTEM, LIGHT, DARK }

data class DesktopState(
    val data: PocketData = PocketData(),
    val selectedDocumentId: String? = null,
    val selectedResultId: String? = null,
    val progress: JobProgress = JobProgress(),
    val message: String? = null,
    val theme: DesktopThemeMode = DesktopThemeMode.SYSTEM,
    val exportDirectory: String = "",
    val helperReport: String? = null,
    val canUndo: Boolean = false,
    val loaded: Boolean = false,
) {
    val selectedDocument: ImportedDocument? get() = data.documents.firstOrNull { it.id == selectedDocumentId }
    val selectedResult: ExtractionResult? get() = data.results.firstOrNull { it.id == selectedResultId }
}

/** A single writer owns workspace and catalog. Every filesystem operation runs off the UI thread. */
class DesktopController(val root: Path) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(DesktopState(exportDirectory = root.resolve("exports").toAbsolutePath().toString()))
    val state: StateFlow<DesktopState> = mutable.asStateFlow()
    private lateinit var store: DesktopStore
    private val busy = AtomicBoolean(true)
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val refreshPending = AtomicBoolean(false)
    private val taskEpoch = AtomicLong(0)
    private val undoHistory = ConcurrentHashMap<String, List<ExtractionResult>>()
    private var currentJob: Job? = null
    private var watcher: WatchService? = null
    private var watchJob: Job? = null

    init {
        currentJob = scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    store = DesktopStore(root.toAbsolutePath().normalize())
                    store.load()
                }
                mutable.value = DesktopState(
                    data = loaded.data,
                    selectedDocumentId = loaded.data.documents.firstOrNull()?.id,
                    selectedResultId = loaded.data.results.firstOrNull()?.id,
                    theme = loaded.theme,
                    exportDirectory = loaded.exportDirectory,
                    message = loaded.message,
                    loaded = true,
                )
                withContext(Dispatchers.IO) { startWatcher(Path.of(loaded.exportDirectory)) }
                refreshPending.set(true)
            } catch (error: Exception) {
                mutable.update { it.copy(message = "无法打开数据目录：" + describe(error), loaded = false) }
            } finally {
                busy.set(false)
                if (closed.get()) scope.cancel()
                else if (refreshPending.getAndSet(false)) refreshExports()
            }
        }
    }

    fun importFiles(files: List<File>) {
        if (files.isEmpty()) return
        task("正在导入 PDF") {
            val epoch = taskEpoch.get()
            val failures = mutableListOf<String>()
            var count = 0
            for (source in files) {
                if (cancelled.get()) break
                var imported: ImportedDocument? = null
                try {
                    var lastProgress = 0L
                    val document = store.importDocument(source, cancelled::get) { done, total ->
                        val now = System.nanoTime()
                        if (now - lastProgress >= 100_000_000L || done == total) {
                            lastProgress = now
                            reportProgress(JobProgress(true,
                            if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
                            "正在导入 " + source.name), epoch)
                        }
                    }
                    imported = document
                    val next = mutable.value.data.copy(documents = listOf(document) + mutable.value.data.documents)
                    store.saveWorkspace(next)
                    publish { it.copy(data = next, selectedDocumentId = document.id, canUndo = undoAvailable(it.selectedResultId)) }
                    count++
                } catch (error: Exception) {
                    imported?.let { runCatching { store.discardImported(it) } }
                    if (cancelled.get() || error is InterruptedIOException) break
                    failures += source.name + "：" + describe(error)
                }
            }
            val summary = if (cancelled.get()) "已取消导入，已保存 " + count + " 份图纸。" else "已导入 " + count + " 份图纸。"
            notice(summary + if (failures.isEmpty()) "" else "\n" + failures.joinToString("\n"))
        }
    }

    fun selectDocument(id: String) = onMain {
        if (mutable.value.data.documents.any { it.id == id }) mutable.update { it.copy(selectedDocumentId = id) }
    }

    fun selectResult(id: String) = onMain {
        if (mutable.value.data.results.any { it.id == id }) mutable.update {
            it.copy(selectedResultId = id, canUndo = undoAvailable(id))
        }
    }

    fun extract(mode: ExtractionMode, documentId: String? = null) {
        val id = documentId ?: mutable.value.selectedDocumentId
        task("正在准备" + mode.title) {
            val data = mutable.value.data
            val document = data.documents.firstOrNull { it.id == id } ?: error("请先导入并选择一份 PDF。")
            val rules = data.rules[mode] ?: RuleSettings()
            val cache = store.cacheDirectory.resolve(document.id)
            Files.createDirectories(cache)
            val engine = OfflinePdfEngine(cache.toFile())
            val epoch = taskEpoch.get()
            val result = engine.extract(document, mode, rules, { progress ->
                reportProgress(progress.copy(running = true), epoch)
            }, cancelled::get)
            // Cancellation is cooperative. Completed pages and partial results are still committed.
            withContext(NonCancellable) {
                val current = mutable.value.data
                val normalized = XlsxExporter.recomputeQuantities(result)
                val next = current.copy(
                    results = listOf(normalized) + current.results,
                    documents = current.documents.map { if (it.id == document.id) it.copy(pageCount = result.pageCount) else it },
                )
                store.saveWorkspace(next)
                publish { it.copy(data = next, selectedResultId = normalized.id, canUndo = false) }
            }
            notice(if (result.completed) "提取完成，结果已自动保存。" else "已保存部分结果；可查看说明并再次提取。")
        }
    }

    fun cancel() {
        if (busy.get()) {
            cancelled.set(true)
            onMain { mutable.update { it.copy(progress = it.progress.copy(message = "正在取消并保存已完成内容…")) } }
        }
    }

    fun saveRules(mode: ExtractionMode, rules: RuleSettings) = task("正在保存规则") {
        require(rules.defaultCn1Cores in 1..100) { "每轴 CN1 容量应为 1–100。" }
        require(listOf(rules.cabinetFilter, rules.excludedCabinets, rules.rotationOverrides, rules.functionOverrides).all { it.length <= 100_000 }) {
            "规则内容过长，请缩短后保存。"
        }
        val next = mutable.value.data.copy(rules = mutable.value.data.rules + (mode to rules))
        saveData(next)
        notice(mode.title + "规则已保存。")
    }

    fun editRecord(id: String, text: String) = editSelected("正在保存标签") { LabelEdits.change(it, id, text) }

    fun addRecord(column: LabelColumn, layout: String, text: String) =
        editSelected("正在补充标签") { LabelEdits.add(it, column, layout, text) }

    fun deleteRecords(ids: Set<String>) {
        if (ids.isEmpty()) return
        editSelected("正在删除标签") { result ->
            require(ids.all { id -> result.records.any {
                it.id == id && it.kind == RecordKind.LABEL && it.column in result.mode.columns && it.column != LabelColumn.QUANTITY
            } }) { "仅能删除当前结果中的实际标签，分组、备用和数量说明不能单独删除。" }
            ids.fold(result) { next, id -> LabelEdits.remove(next, id) }
        }
    }

    private fun editSelected(description: String, transform: (ExtractionResult) -> ExtractionResult) {
        val selectedId = mutable.value.selectedResultId
        task(description) {
            val before = mutable.value.data.results.firstOrNull { it.id == selectedId } ?: error("请先选择提取结果。")
            val after = transform(before)
            if (after == before) return@task
            val next = mutable.value.data.copy(results = mutable.value.data.results.map { if (it.id == before.id) after else it })
            store.saveWorkspace(next)
            undoHistory[before.id] = ((undoHistory[before.id] ?: emptyList()) + before).takeLast(20)
            publish { it.copy(data = next, canUndo = undoAvailable(it.selectedResultId)) }
            notice("修改已保存，可撤销。")
        }
    }

    fun undo() {
        val id = mutable.value.selectedResultId
        task("正在撤销修改") {
            val history = id?.let { undoHistory[it] } ?: error("当前没有可撤销的修改。")
            val previous = history.lastOrNull() ?: error("当前没有可撤销的修改。")
            require(mutable.value.data.results.any { it.id == previous.id }) { "原结果已不存在。" }
            val next = mutable.value.data.copy(results = mutable.value.data.results.map { if (it.id == previous.id) previous else it })
            store.saveWorkspace(next)
            undoHistory[previous.id] = history.dropLast(1)
            publish { it.copy(data = next, canUndo = undoAvailable(it.selectedResultId)) }
            notice("已撤销并保存。")
        }
    }

    fun exportCurrent() {
        val id = mutable.value.selectedResultId
        task("正在生成 Excel") {
            val result = mutable.value.data.results.firstOrNull { it.id == id } ?: error("请先选择提取结果。")
            require(result.records.isNotEmpty()) { "当前结果没有可导出的内容。" }
            val directory = Path.of(mutable.value.exportDirectory).toAbsolutePath().normalize()
            val file = DesktopExportFiles.writeNew(result, directory, cancelled::get)
            val entry = ExportedFile(
                UUID.randomUUID().toString(), result.id, result.documentName, result.mode,
                file.fileName.toString(), file.toString(), System.currentTimeMillis(),
                Files.size(file), completed = result.completed, storageKey = DesktopExportFiles.key(file),
            )
            val current = mutable.value.data.exports
            val next = listOf(entry) + current.filterNot { Path.of(it.uri).toAbsolutePath().normalize() == file }
            try {
                store.saveExports(next)
            } catch (error: Exception) {
                // The generated file is real and remains recoverable by directory refresh.
                throw IOException("Excel 已生成到 " + file + "，但文件记录未保存，请检查磁盘后刷新。", error)
            }
            publish { it.copy(data = it.data.copy(exports = next)) }
            notice("已导出：" + file.fileName)
            startWatcher(directory)
        }
    }

    fun favoriteExport(id: String) = updateExports("正在保存收藏") { files ->
        require(files.any { it.id == id }) { "文件记录已不存在。" }
        files.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
    }

    /** Changes the app display name only; the real filesystem name remains visible in file details. */
    fun renameExport(id: String, name: String) = updateExports("正在保存显示名称") { files ->
        val clean = name.trim()
        require(clean.isNotBlank() && clean.length <= 180 && clean.none { it.isISOControl() }) { "显示名称应为 1–180 字且不能换行。" }
        require(files.any { it.id == id }) { "文件记录已不存在。" }
        files.map { if (it.id == id) it.copy(displayName = clean) else it }
    }

    fun deleteExport(id: String) = task("正在删除 Excel") {
        val file = mutable.value.data.exports.firstOrNull { it.id == id } ?: error("文件记录已不存在。")
        DesktopExportFiles.delete(file, Path.of(mutable.value.exportDirectory), store.defaultExportDirectory)
        val next = mutable.value.data.exports.filterNot { it.id == id }
        try {
            store.saveExports(next)
        } catch (error: Exception) {
            throw IOException("文件已删除，但文件索引未能保存，请刷新并检查数据目录权限。", error)
        }
        publish { it.copy(data = it.data.copy(exports = next)) }
        notice("Excel 文件已删除。")
    }

    private fun updateExports(description: String, transform: (List<ExportedFile>) -> List<ExportedFile>) = task(description) {
        val next = transform(mutable.value.data.exports)
        store.saveExports(next)
        publish { it.copy(data = it.data.copy(exports = next)) }
    }

    fun refreshExports() {
        if (closed.get()) return
        if (busy.get()) { refreshPending.set(true); return }
        task("", quiet = true) {
            val before = mutable.value.data.exports
            val scan = DesktopExportFiles.scan(Path.of(mutable.value.exportDirectory), before)
            if (scan.files != before) {
                store.saveExports(scan.files)
                publish { it.copy(data = it.data.copy(exports = scan.files)) }
            }
            if (scan.warning != null) notice(scan.warning)
            if (watchJob?.isActive != true) startWatcher(Path.of(mutable.value.exportDirectory))
        }
    }

    fun deleteDocument(id: String) = task("正在删除图纸副本") {
        val document = mutable.value.data.documents.firstOrNull { it.id == id } ?: error("图纸已不存在。")
        val previous = mutable.value.data
        val resultIds = previous.results.filter { it.documentId == id }.map { it.id }.toSet()
        val next = previous.copy(documents = previous.documents.filterNot { it.id == id }, results = previous.results.filterNot { it.documentId == id })
        val warning = store.removeDocuments(listOf(document), next)
        resultIds.forEach { undoHistory.remove(it) }
        publish {
            val selectedResult = it.selectedResultId?.takeUnless { selected -> selected in resultIds } ?: next.results.firstOrNull()?.id
            it.copy(data = next,
            selectedDocumentId = it.selectedDocumentId?.takeUnless { selected -> selected == id } ?: next.documents.firstOrNull()?.id,
            selectedResultId = selectedResult, canUndo = undoAvailable(selectedResult)) }
        notice(warning ?: "已删除应用中的图纸副本及对应结果；原 PDF 和导出 Excel 保留。")
    }

    fun clearDocuments() = task("正在清空图纸副本") {
        val previous = mutable.value.data
        val next = previous.copy(documents = emptyList(), results = emptyList())
        val warning = store.removeDocuments(previous.documents, next)
        undoHistory.clear()
        publish { it.copy(data = next, selectedDocumentId = null, selectedResultId = null, canUndo = false) }
        notice(warning ?: "应用图纸副本、结果和缓存已清空；原 PDF、规则和导出 Excel 保留。")
    }

    fun deleteResult(id: String) = task("正在删除结果") {
        require(mutable.value.data.results.any { it.id == id }) { "结果已不存在。" }
        val next = mutable.value.data.copy(results = mutable.value.data.results.filterNot { it.id == id })
        store.saveWorkspace(next)
        undoHistory.remove(id)
        publish {
            val selectedResult = it.selectedResultId?.takeUnless { selected -> selected == id } ?: next.results.firstOrNull()?.id
            it.copy(data = next, selectedResultId = selectedResult, canUndo = undoAvailable(selectedResult)) }
        notice("结果已删除；图纸和导出文件保留。")
    }

    fun setTheme(mode: DesktopThemeMode) = task("正在保存外观") {
        store.savePreferences(mode, mutable.value.exportDirectory)
        publish { it.copy(theme = mode) }
    }

    fun setExportDirectory(path: String) = task("正在设置导出目录") {
        val directory = Path.of(path).toAbsolutePath().normalize()
        Files.createDirectories(directory)
        require(Files.isDirectory(directory) && Files.isReadable(directory) && Files.isWritable(directory)) { "请选择可读取和写入的目录。" }
        store.savePreferences(mutable.value.theme, directory.toString())
        publish { it.copy(exportDirectory = directory.toString()) }
        startWatcher(directory)
        refreshPending.set(true)
        notice("导出目录已保存。")
    }

    fun runDiagnostics() = task("正在检查本地文件") {
        val report = DesktopPythonTools.diagnose(mutable.value.data, cancelled::get)
        if (cancelled.get()) throw InterruptedIOException("已取消本地检查。")
        publish { it.copy(helperReport = report) }
        notice("本地文件检查完成。")
    }

    fun clearMessage() = onMain { mutable.update { it.copy(message = null) } }

    fun openFile(path: String) = task("正在打开文件") {
        val file = Path.of(path).toAbsolutePath().normalize().toFile()
        require(file.isFile && file.canRead()) { "文件不存在或无法读取，请刷新后重试。" }
        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { "当前系统无法打开文件，请从文件夹手动打开。" }
        Desktop.getDesktop().open(file)
    }

    fun openDataFolder() = openFolder(root)
    fun openExportFolder() = openFolder(Path.of(mutable.value.exportDirectory))

    private fun openFolder(path: Path) = task("正在打开文件夹") {
        Files.createDirectories(path)
        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { "当前系统无法打开文件夹：" + path }
        Desktop.getDesktop().open(path.toFile())
    }

    private suspend fun saveData(next: PocketData) {
        store.saveWorkspace(next)
        publish { it.copy(data = next, canUndo = undoAvailable(it.selectedResultId)) }
    }

    private fun task(description: String, quiet: Boolean = false, body: suspend () -> Unit) {
        if (closed.get()) return
        if (!mutable.value.loaded) { onMain { mutable.update { it.copy(message = "数据尚未读取完成，请稍候。") } }; return }
        if (!busy.compareAndSet(false, true)) {
            if (quiet) refreshPending.set(true)
            else onMain { mutable.update { it.copy(message = "当前任务尚未完成，请等待或取消后再操作。") } }
            return
        }
        cancelled.set(false)
        taskEpoch.incrementAndGet()
        currentJob = scope.launch {
            if (!quiet) mutable.update { it.copy(progress = JobProgress(true, 0f, description)) }
            try {
                withContext(Dispatchers.IO) { body() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(message = if (error is InterruptedIOException) "任务已取消。" else describe(error)) }
            } catch (error: OutOfMemoryError) {
                mutable.update { it.copy(message = "内存不足，当前任务未完成；已保存的数据保留，请关闭大文件后重试。") }
            } finally {
                taskEpoch.incrementAndGet()
                mutable.update { it.copy(progress = JobProgress()) }
                busy.set(false)
                if (closed.get()) scope.cancel()
                else if (refreshPending.getAndSet(false)) refreshExports()
            }
        }
    }

    private suspend fun publish(transform: (DesktopState) -> DesktopState) = withContext(Dispatchers.Main.immediate) { mutable.update(transform) }
    private suspend fun notice(message: String) = publish { it.copy(message = message) }
    private fun undoAvailable(id: String?) = id?.let { undoHistory[it]?.isNotEmpty() } == true
    private fun onMain(action: () -> Unit) { if (!closed.get()) scope.launch { action() } }
    private fun reportProgress(progress: JobProgress, epoch: Long) = scope.launch {
        if (busy.get() && taskEpoch.get() == epoch && !cancelled.get()) mutable.update { it.copy(progress = progress) }
    }

    private fun startWatcher(directory: Path) {
        runCatching { watcher?.close() }
        watchJob?.cancel()
        watcher = null
        try {
            if (!Files.isDirectory(directory)) return
            val service = directory.fileSystem.newWatchService()
            watcher = service
            directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY)
            watchJob = scope.launch(Dispatchers.IO) {
                try {
                    while (!closed.get()) {
                        val key = service.take()
                        val changed = key.pollEvents().any { event ->
                            event.kind() == StandardWatchEventKinds.OVERFLOW || event.context().toString().endsWith(".xlsx", true)
                        }
                        if (!key.reset()) { refreshExports(); break }
                        if (changed) { delay(250); refreshExports() }
                    }
                } catch (_: ClosedWatchServiceException) {
                    // Directory switch and application shutdown deliberately close the watcher.
                } catch (_: InterruptedException) {
                    // Shutdown.
                } catch (_: CancellationException) {
                    // Switching folders cancels an old debounce delay.
                } catch (error: Exception) {
                    onMain { mutable.update { it.copy(message = "目录监听暂不可用，请使用文件页刷新：" + describe(error)) } }
                }
            }
        } catch (error: Exception) {
            runCatching { watcher?.close() }
            watcher = null
            onMain { mutable.update { it.copy(message = "目录监听暂不可用，请使用文件页刷新：" + describe(error)) } }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelled.set(true)
        refreshPending.set(false)
        runCatching { watcher?.close() }
        watchJob?.cancel()
        if (!busy.get()) scope.cancel()
    }

    /** Allows the window-close flow to await partial-result saving without blocking the UI thread. */
    suspend fun awaitIdle() {
        do {
            while (busy.get() || refreshPending.get()) delay(25)
            delay(25)
        } while (busy.get() || refreshPending.get())
    }

    private fun describe(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}

/** Filesystem logic is independently testable without an app window or a controller dispatcher. */
internal object DesktopExportFiles {
    data class Scan(val files: List<ExportedFile>, val warning: String? = null)

    fun key(path: Path): String {
        val real = path.toRealPath()
        val attributes = Files.readAttributes(real, BasicFileAttributes::class.java)
        return real.parent.toString() + "|" + (attributes.fileKey()?.toString() ?: real.toString())
    }

    fun writeNew(result: ExtractionResult, directory: Path, cancelled: () -> Boolean = { false }): Path {
        Files.createDirectories(directory)
        val name = DesktopFileNames.safe(XlsxExporter.fileName(result), "xlsx")
        val base = name.removeSuffix(".xlsx")
        val temporary = directory.resolve("." + UUID.randomUUID() + ".part")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val output = java.nio.channels.Channels.newOutputStream(channel)
                XlsxExporter.write(result, output)
                output.flush()
                channel.force(true)
            }
            if (cancelled()) throw InterruptedIOException("已取消导出。")
            for (index in 0..9999) {
                val target = directory.resolve(if (index == 0) name else base + " (" + index + ").xlsx").toAbsolutePath().normalize()
                var created = false
                try {
                    FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                        created = true
                        Files.newInputStream(temporary).use { input ->
                            val output = java.nio.channels.Channels.newOutputStream(channel)
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw InterruptedIOException("已取消导出。")
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                            channel.force(true)
                        }
                    }
                    return target
                } catch (_: FileAlreadyExistsException) {
                    // CREATE_NEW is the collision boundary; existing exports are never overwritten.
                } catch (error: Throwable) {
                    if (created) runCatching { Files.deleteIfExists(target) }
                    throw error
                }
            }
            error("同名导出文件过多，请整理导出目录。")
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun scan(directory: Path, previous: List<ExportedFile>): Scan {
        val normalized = directory.toAbsolutePath().normalize()
        try {
            if (!Files.exists(normalized)) {
                if (!Files.notExists(normalized) || normalized.parent == null || !Files.isReadable(normalized.parent)) {
                    return Scan(previous, "导出目录暂时无法读取，已保留文件记录。")
                }
                return Scan(previous.filterNot { pathOf(it)?.parent == normalized }, "导出目录已不存在，请重新选择目录或导出。")
            }
            require(Files.isDirectory(normalized) && Files.isReadable(normalized)) { "导出目录暂时无法读取，已保留文件记录。" }
            val byPath = previous.associateBy { pathOf(it) }
            val byKey = previous.filter { it.storageKey.isNotBlank() }.associateBy { it.storageKey }
            val matched = mutableSetOf<String>()
            val observed = mutableSetOf<Path>()
            val files = mutableListOf<ExportedFile>()
            val uncertain = mutableSetOf<Path>()
            Files.newDirectoryStream(normalized).use { entries ->
                entries.forEach { path ->
                    if (!path.fileName.toString().endsWith(".xlsx", true) || Files.isSymbolicLink(path)) return@forEach
                    try {
                        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (!attributes.isRegularFile) return@forEach
                        val absolute = path.toAbsolutePath().normalize()
                        val storageKey = key(absolute)
                        val old = (byPath[absolute]?.takeIf { it.storageKey.isBlank() || it.storageKey == storageKey }
                            ?: byKey[storageKey])?.takeUnless { it.id in matched }
                        val filename = path.fileName.toString()
                        val entry = old?.copy(name = filename, uri = absolute.toString(), bytes = attributes.size(),
                            displayName = if (old.displayName == old.name) filename else old.displayName,
                            storageKey = storageKey)
                            ?: ExportedFile(UUID.randomUUID().toString(), "", "", ExtractionMode.FULL,
                                filename, absolute.toString(), attributes.creationTime().toMillis(), attributes.size(), storageKey = storageKey)
                        matched += entry.id
                        observed.add(absolute)
                        files += entry
                    } catch (_: NoSuchFileException) {
                        // A file removed during enumeration will be confirmed absent below.
                    } catch (_: IOException) {
                        uncertain.add(path.toAbsolutePath().normalize())
                    } catch (_: SecurityException) {
                        uncertain.add(path.toAbsolutePath().normalize())
                    }
                }
            }
            previous.filter { it.id !in matched }.forEach { old ->
                val path = pathOf(old)
                if (path !in observed && (path?.parent != normalized || path in uncertain || (path != null && !Files.notExists(path)))) files += old
            }
            return Scan(files.sortedByDescending { it.createdAt }, if (uncertain.isEmpty()) null else "部分文件暂时无法读取，已保留其记录。")
        } catch (error: Exception) {
            return Scan(previous, error.message ?: "导出目录暂时无法读取，已保留文件记录。")
        }
    }

    fun delete(file: ExportedFile, directory: Path, defaultDirectory: Path) {
        val path = pathOf(file) ?: error("文件路径无效，未删除。")
        val allowed = setOf(directory.toAbsolutePath().normalize(), defaultDirectory.toAbsolutePath().normalize())
        require(path.parent in allowed && path.fileName.toString().endsWith(".xlsx", true) && !Files.isSymbolicLink(path)) {
            "文件不在当前导出目录，未删除；请切换到其所在导出目录后重试。"
        }
        if (Files.notExists(path)) return
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "文件不是可删除的 Excel。" }
        require(path.toRealPath().parent == path.parent.toRealPath()) { "拒绝删除目录外文件。" }
        require(file.storageKey.isBlank() || file.storageKey == key(path)) { "文件已被外部替换，请刷新后确认新的文件再删除。" }
        Files.delete(path)
    }

    private fun pathOf(file: ExportedFile): Path? = runCatching { Path.of(file.uri).toAbsolutePath().normalize() }.getOrNull()
}
