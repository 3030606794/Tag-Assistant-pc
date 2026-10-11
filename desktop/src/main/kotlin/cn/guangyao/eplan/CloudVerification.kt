@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package cn.guangyao.eplan

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import javax.swing.SwingUtilities

/** CI-only paths are explicit. Normal launches never create examples or test documents. */
internal fun renderCloudPreviews(output: File) {
    output.mkdirs()
    val temporary = Files.createTempDirectory("eplan-pc-cloud-preview").toFile()
    try {
        val source = File("engine/src/test/resources/fixtures/layout-cabinet-native.pdf").absoluteFile
        require(source.isFile) { "Cloud fixture unavailable" }
        val document = ImportedDocument("cloud-preview", source.name, source.absolutePath, source.length(), 0)
        val result = OfflinePdfEngine(File(temporary, "cache")).extract(document, ExtractionMode.FULL,
            RuleSettings(), {}, { false })
        require(result.completed && result.records.any { it.kind == RecordKind.LABEL }) { "Real fixture did not extract" }
        val exported = File(temporary, XlsxExporter.fileName(result))
        exported.outputStream().use { XlsxExporter.write(result, it) }
        val file = ExportedFile("cloud-export", result.id, document.name, result.mode,
            exported.name, exported.absolutePath, System.currentTimeMillis(), exported.length())
        val data = PocketData(listOf(document.copy(pageCount = result.pageCount)), listOf(result), exports = listOf(file))
        val ready = DesktopState(data, document.id, result.id, loaded = true, exportDirectory = exported.parent)
        val states = listOf(
            Triple("empty", DesktopState(loaded = true, exportDirectory = exported.parent), "workspace"),
            Triple("workspace", ready, "workspace"),
            Triple("files", ready, "files"),
            Triple("settings", ready, "settings"),
        )
        for ((name, state, page) in states) for (dark in listOf(false, true)) {
            val target = File(output, "$name-${if (dark) "dark" else "light"}.png")
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(1440, 900) {
                    DesktopTheme(dark) { DesktopContent(state, DesktopActions(), initialPage = page,
                        initialSourceRecordId = state.selectedResult?.records?.firstOrNull {
                            it.kind == RecordKind.LABEL && it.page > 0 && it.width > 0 && it.height > 0
                        }?.id) }
                }
            }
            try {
                repeat(180) { frame ->
                    SwingUtilities.invokeAndWait { scene.render(frame * 16_666_667L).close() }
                    Thread.sleep(20)
                }
                SwingUtilities.invokeAndWait {
                    scene.render(3_500_000_000L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)?.use { encoded -> target.writeBytes(encoded.bytes) }
                            ?: error("Unable to encode preview")
                    }
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
            require(target.length() > 1000)
            println("Rendered ${target.name}")
        }
        File(output, "fixture-summary.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "fixture" to document.name, "completed" to result.completed, "pages" to result.pageCount,
            "columns" to LabelColumn.entries.associate { it.letter to result.records.count { r -> r.column == it } },
            "issues" to result.issues.size)))
    } finally { temporary.deleteRecursively() }
}

/** Runs from the actual self-contained Windows executable, including bundled Python. */
internal fun runPackagedSmoke(output: File, fixture: File) = runBlocking {
    require(fixture.isFile)
    output.mkdirs()
    val root = File(output, "workspace").toPath()
    val controller = DesktopController(root)
    var resumed: DesktopController? = null
    suspend fun idle(target: DesktopController = controller) {
        withTimeout(180_000) { target.awaitIdle() }
        require(target.state.value.loaded) { target.state.value.message ?: "Workspace failed to load" }
    }
    try {
        idle()
        controller.importFiles(listOf(fixture))
        idle()
        require(controller.state.value.data.documents.size == 1)
        val document = controller.state.value.selectedDocument ?: error("Imported document not selected")
        require(document.name == fixture.name && File(document.path).isFile)
        controller.extract(ExtractionMode.FULL)
        idle()
        val original = controller.state.value.selectedResult ?: error("No extraction result")
        require(original.completed && original.records.any { it.kind == RecordKind.LABEL })
        val editable = original.records.firstOrNull { it.kind == RecordKind.LABEL && it.column == LabelColumn.FUNCTION }
            ?: error("Expected a native function label")
        controller.editRecord(editable.id, "PC 初版编辑验证")
        idle()
        require(controller.state.value.selectedResult!!.records.any { it.id == editable.id && it.text == "PC 初版编辑验证" })
        controller.undo()
        idle()
        require(controller.state.value.selectedResult!!.records == original.records)
        controller.exportCurrent()
        idle()
        controller.exportCurrent()
        idle()
        val exports = controller.state.value.data.exports
        require(exports.size == 2 && exports.map { it.uri }.distinct().size == 2)
        exports.forEach { entry -> ZipFile(entry.uri).use { require(it.getEntry("xl/worksheets/sheet1.xml") != null) } }
        controller.favoriteExport(exports.first().id)
        idle()
        controller.setTheme(DesktopThemeMode.DARK)
        idle()
        controller.runDiagnostics()
        idle()
        require(controller.state.value.helperReport?.contains("Excel") == true) { controller.state.value.message ?: "Python helper failed" }
        controller.close()
        resumed = DesktopController(root)
        idle(resumed)
        val restored = resumed.state.value
        require(restored.theme == DesktopThemeMode.DARK && restored.data.documents.size == 1 && restored.data.results.size == 1)
        require(restored.data.exports.size == 2 && restored.data.exports.any { it.favorite })
        val victim = restored.data.exports.first()
        resumed.deleteExport(victim.id)
        idle(resumed)
        require(!File(victim.uri).exists() && resumed.state.value.data.exports.size == 1)
        val report = mapOf("ok" to true, "version" to "0.1.0", "fixture" to fixture.name,
            "pages" to original.pageCount, "records" to original.records.size,
            "checks" to listOf("bundled runtime", "PDF import", "native extraction", "edit and undo", "non-overwriting XLSX export",
                "favorite persistence", "theme persistence", "restart reload", "actual file deletion", "bundled offline Python"))
        File(output, "smoke-result.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
    } finally {
        controller.close()
        resumed?.close()
    }
}
