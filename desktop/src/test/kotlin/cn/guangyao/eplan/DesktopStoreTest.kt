package cn.guangyao.eplan

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DesktopStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun directory(name: String = UUID.randomUUID().toString()): Path = temporary.newFolder(name).toPath()

    @Test fun workspaceAndIndependentCatalogRoundTripAllSourceMetadataAndPreferences() {
        val root = directory()
        val store = DesktopStore(root)
        val result = sampleDesktopResult()
        val export = sampleDesktopExport(root.resolve("exports/真实.xlsx")).copy(favorite = true, displayName = "用户备注")
        val rules = RuleSettings("C01", "C02", "C01=90", "1KM01=运行", 8, false)
        val data = PocketData(results = listOf(result), rules = mapOf(ExtractionMode.DEVICE to rules))
        store.saveWorkspace(data)
        store.saveExports(listOf(export))
        store.savePreferences(DesktopThemeMode.DARK, root.resolve("exports").toString())

        val loaded = DesktopStore(root).load()
        assertEquals(result, loaded.data.results.single())
        assertEquals(rules, loaded.data.rules[ExtractionMode.DEVICE])
        assertEquals(export, loaded.data.exports.single())
        assertEquals(DesktopThemeMode.DARK, loaded.theme)
        assertNull(loaded.message)
    }

    @Test fun emptyCatalogIsAuthoritativeOverLegacyWorkspaceExportSnapshot() {
        val root = directory()
        val store = DesktopStore(root)
        store.saveWorkspace(PocketData())
        val path = root.resolve("workspace.json")
        val old = sampleDesktopExport(root.resolve("exports/旧.xlsx"))
        val workspace = Files.readString(path).trimEnd().dropLast(1) + ",\"exports\":[" + Gson().toJson(old) + "]}"
        Files.writeString(path, workspace)
        store.saveExports(emptyList())

        assertTrue(DesktopStore(root).load().data.exports.isEmpty())
    }

    @Test fun malformedOrIncompleteCatalogPreservesRecoveryBytesAndFallsBackToLegacy() {
        for (malformed in listOf("{\"version\":1}", "{\"version\":1,\"exports\":[]} true")) {
            val root = directory()
            val store = DesktopStore(root)
            store.saveWorkspace(PocketData())
            val old = sampleDesktopExport(root.resolve("exports/旧.xlsx"))
            val workspacePath = root.resolve("workspace.json")
            Files.writeString(workspacePath, Files.readString(workspacePath).trimEnd().dropLast(1) +
                ",\"exports\":[" + Gson().toJson(old) + "]}")
            Files.writeString(root.resolve("exports-index.json"), malformed)

            val loaded = DesktopStore(root).load()
            assertEquals(listOf(old), loaded.data.exports)
            assertTrue(loaded.message!!.contains("文件索引"))
            val recovery = Files.newDirectoryStream(root, "exports-index.json.recovery-*").use { it.toList().single() }
            assertEquals(malformed, Files.readString(recovery))
        }
    }

    @Test fun corruptWorkspaceIsBackedUpRatherThanSilentlyOverwritten() {
        val root = directory()
        DesktopStore(root)
        val corrupt = "{\"version\":1,\"documents\":"
        Files.writeString(root.resolve("workspace.json"), corrupt)

        val loaded = DesktopStore(root).load()
        assertTrue(loaded.data.documents.isEmpty())
        assertNotNull(loaded.message)
        assertEquals(corrupt, Files.readString(root.resolve("workspace.json")))
        val recovery = Files.newDirectoryStream(root, "workspace.json.recovery-*").use { it.toList().single() }
        assertEquals(corrupt, Files.readString(recovery))
    }

    @Test fun failedAtomicCommitKeepsExistingDestinationAndCleansTemporaryFile() {
        val root = directory()
        val store = DesktopStore(root)
        val destination = Files.createDirectory(root.resolve("workspace.json"))
        Files.writeString(destination.resolve("must-remain.txt"), "original")

        assertThrows(Exception::class.java) { store.saveWorkspace(PocketData()) }

        assertEquals("original", Files.readString(destination.resolve("must-remain.txt")))
        assertTrue(Files.newDirectoryStream(root, "*.part").use { it.toList().isEmpty() })
    }

    @Test fun streamingImportPreservesOriginalFilenameAndNeverModifiesSource() {
        val root = directory()
        val source = File(temporary.root, "机台 名称.PDF").apply { writeText("%PDF-1.7\n" + "x".repeat(180_000)) }
        val original = source.readBytes()
        val store = DesktopStore(root)
        var progress = 0L

        val document = store.importDocument(source, { false }) { copied, _ -> progress = copied }
        store.saveWorkspace(PocketData(documents = listOf(document)))

        assertEquals(source.name, document.name)
        assertEquals(source.length(), progress)
        assertArrayEquals(original, Path.of(document.path).toFile().readBytes())
        assertArrayEquals(original, source.readBytes())
        assertEquals(document, DesktopStore(root).load().data.documents.single())
        assertEquals(document.id, Path.of(document.path).parent.fileName.toString())
    }

    @Test fun cancelledOrInvalidImportLeavesNoFormalDocumentAndKeepsSource() {
        val root = directory()
        val store = DesktopStore(root)
        val source = File(temporary.root, "cancel.pdf").apply { writeText("%PDF-1.7\n" + "x".repeat(2048)) }
        assertThrows(java.io.InterruptedIOException::class.java) { store.importDocument(source, { true }) { _, _ -> } }
        val invalid = File(temporary.root, "invalid.pdf").apply { writeText("not a PDF") }
        assertThrows(IllegalArgumentException::class.java) { store.importDocument(invalid, { false }) { _, _ -> } }
        assertTrue(source.exists())
        assertTrue(invalid.exists())
        assertTrue(Files.newDirectoryStream(store.documentsDirectory).use { it.toList().isEmpty() })
    }

    @Test fun deleteStagingRestoresDocumentWhenWorkspaceCommitFails() {
        val root = directory()
        val store = DesktopStore(root)
        val source = File(temporary.root, "preserve.pdf").apply { writeText("%PDF-1.7\n") }
        val document = store.importDocument(source, { false }) { _, _ -> }
        Files.createDirectory(root.resolve("workspace.json"))
        Files.writeString(root.resolve("workspace.json/occupied"), "keep")

        assertThrows(Exception::class.java) { store.removeDocuments(listOf(document), PocketData()) }

        assertTrue(Files.exists(Path.of(document.path)))
        assertTrue(source.exists())
    }

    @Test fun startupRestoresAppCopyStagedBeforeInterruptedMetadataCommit() {
        val root = directory()
        val store = DesktopStore(root)
        val source = File(temporary.root, "interrupted.pdf").apply { writeText("%PDF-1.7\n") }
        val document = store.importDocument(source, { false }) { _, _ -> }
        store.saveWorkspace(PocketData(documents = listOf(document)))
        val original = Path.of(document.path).parent
        Files.move(original, store.documentsDirectory.resolve(".trash-" + document.id + "-" + UUID.randomUUID()))

        val loaded = DesktopStore(root).load()

        assertTrue(Files.exists(Path.of(loaded.data.documents.single().path)))
        assertTrue(loaded.message!!.contains("已恢复"))
    }

    @Test fun clearCopiesKeepsRulesExportsAndOriginalSourceAndRemovesOnlyOwnedCache() {
        val root = directory()
        val store = DesktopStore(root)
        val source = File(temporary.root, "original.pdf").apply { writeText("%PDF-1.7\n") }
        val document = store.importDocument(source, { false }) { _, _ -> }
        val result = sampleDesktopResult(document.id)
        val exportPath = store.defaultExportDirectory.resolve("keep.xlsx")
        Files.writeString(exportPath, "existing export")
        val export = sampleDesktopExport(exportPath)
        val rules = mapOf(ExtractionMode.DEVICE to RuleSettings(defaultCn1Cores = 9))
        store.saveWorkspace(PocketData(listOf(document), listOf(result), rules))
        store.saveExports(listOf(export))
        Files.createDirectories(store.cacheDirectory.resolve(document.id))
        Files.writeString(store.cacheDirectory.resolve(document.id).resolve("page.cache"), "cache")
        Files.createDirectories(store.cacheDirectory.resolve("unrelated"))
        Files.writeString(store.cacheDirectory.resolve("unrelated/keep"), "keep")

        store.removeDocuments(listOf(document), PocketData(rules = rules))
        val loaded = DesktopStore(root).load()

        assertTrue(loaded.data.documents.isEmpty())
        assertTrue(loaded.data.results.isEmpty())
        assertEquals(rules, loaded.data.rules)
        assertEquals(listOf(export), loaded.data.exports)
        assertTrue(source.exists())
        assertTrue(Files.exists(exportPath))
        assertFalse(Files.exists(Path.of(document.path)))
        assertFalse(Files.exists(store.cacheDirectory.resolve(document.id)))
        assertTrue(Files.exists(store.cacheDirectory.resolve("unrelated/keep")))
    }

    @Test fun metadataCannotDeleteDocumentOutsideOwnedCopies() {
        val root = directory()
        val store = DesktopStore(root)
        val source = File(temporary.root, "outside.pdf").apply { writeText("%PDF-1.7\n") }
        val document = ImportedDocument(UUID.randomUUID().toString(), source.name, source.absolutePath, source.length(), 1)
        assertThrows(IllegalArgumentException::class.java) { store.removeDocuments(listOf(document), PocketData()) }
        assertTrue(source.exists())
    }
}

class DesktopExportFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repeatedExportsNeverOverwriteAndRemainActualTextOnlyXlsx() {
        val directory = temporary.newFolder().toPath()
        val result = sampleDesktopResult().copy(documentName = "CON.pdf")
        val first = DesktopExportFiles.writeNew(result, directory)
        val original = Files.readAllBytes(first)
        val second = DesktopExportFiles.writeNew(result, directory)

        assertEquals("_CON.xlsx", first.fileName.toString())
        assertEquals("_CON (1).xlsx", second.fileName.toString())
        assertArrayEquals(original, Files.readAllBytes(first))
        java.util.zip.ZipFile(second.toFile()).use { zip ->
            val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().use { it.readText() }
            assertTrue(sheet.contains("CON.pdf"))
            assertTrue(sheet.contains("1KM01"))
            assertTrue(sheet.contains("inlineStr"))
            assertFalse(sheet.contains("<f>"))
        }
    }

    @Test fun windowsReservedNamesInvalidCharactersAndSurrogateTruncationAreHandled() {
        for (reserved in listOf("CON", "prn", "AUX", "NUL", "COM1", "LPT9", "CON.notes")) {
            assertTrue(DesktopFileNames.safe(reserved + ".xlsx", "xlsx").startsWith("_"))
        }
        val cleaned = DesktopFileNames.safe("ab<>:\"|?*. .xlsx", "xlsx")
        assertFalse(cleaned.any { it in "<>:\"|?*" })
        assertTrue(cleaned.endsWith(".xlsx"))
        val unicode = DesktopFileNames.safe("a".repeat(119) + "\uD83D\uDE00" + ".xlsx", "xlsx").removeSuffix(".xlsx")
        assertFalse(unicode.last().isHighSurrogate())
    }

    @Test fun cancelledExportRemovesPartialFileAndKeepsExistingNeighbors() {
        val directory = temporary.newFolder().toPath()
        val neighbor = directory.resolve("neighbor.xlsx")
        Files.writeString(neighbor, "keep")
        assertThrows(java.io.InterruptedIOException::class.java) {
            DesktopExportFiles.writeNew(sampleDesktopResult(), directory) { true }
        }
        assertEquals(listOf(neighbor), Files.newDirectoryStream(directory).use { it.toList() })
        assertEquals("keep", Files.readString(neighbor))
    }

    @Test fun scannerKeepsMetadataWhenExternalFileIsRenamedAndDiscoversUnknownSourceHonestly() {
        val directory = temporary.newFolder().toPath()
        val file = DesktopExportFiles.writeNew(sampleDesktopResult(), directory)
        val old = sampleDesktopExport(file).copy(favorite = true, displayName = "收藏备注", storageKey = DesktopExportFiles.key(file))
        val renamed = Files.move(file, directory.resolve("外部改名.xlsx"))
        Files.writeString(directory.resolve("外部文件.xlsx"), "a local file")

        val scan = DesktopExportFiles.scan(directory, listOf(old))

        val retained = scan.files.single { it.id == old.id }
        assertEquals(renamed.toString(), retained.uri)
        assertEquals("外部改名.xlsx", retained.name)
        assertEquals("收藏备注", retained.displayName)
        assertTrue(retained.favorite)
        assertEquals(old.resultId, retained.resultId)
        val recovered = scan.files.single { it.id != old.id }
        assertEquals("", recovered.resultId)
        assertEquals("", recovered.documentName)
    }

    @Test fun directoryReadFailureDoesNotEraseCatalogAndConfirmedMissingFolderDoes() {
        val parent = temporary.newFolder().toPath()
        val blocked = parent.resolve("not-directory")
        Files.writeString(blocked, "file")
        val old = sampleDesktopExport(blocked.resolve("old.xlsx"))
        val failure = DesktopExportFiles.scan(blocked, listOf(old))
        assertEquals(listOf(old), failure.files)
        assertNotNull(failure.warning)

        val directory = Files.createDirectory(parent.resolve("removed"))
        val owned = sampleDesktopExport(directory.resolve("gone.xlsx"))
        val unrelated = sampleDesktopExport(parent.resolve("other/keep.xlsx")).copy(id = "other")
        Files.delete(directory)
        assertEquals(listOf(unrelated), DesktopExportFiles.scan(directory, listOf(owned, unrelated)).files)
    }

    @Test fun samePathReplacementDoesNotInheritSourceOrFavoriteAndStaleDeleteIsRefused() {
        val directory = temporary.newFolder().toPath()
        val target = DesktopExportFiles.writeNew(sampleDesktopResult(), directory)
        val previous = sampleDesktopExport(target).copy(favorite = true, storageKey = DesktopExportFiles.key(target))
        val replacement = directory.resolve("new-content.part")
        Files.writeString(replacement, "a different externally created file")
        Files.delete(target)
        Files.move(replacement, target)

        assertThrows(IllegalArgumentException::class.java) {
            DesktopExportFiles.delete(previous, directory, directory)
        }
        val scan = DesktopExportFiles.scan(directory, listOf(previous))

        assertEquals(1, scan.files.size)
        assertNotEquals(previous.id, scan.files.single().id)
        assertEquals("", scan.files.single().resultId)
        assertFalse(scan.files.single().favorite)
        assertEquals("a different externally created file", Files.readString(target))
    }

    @Test fun trueDeletionRemovesOnlyChosenXlsxAndMissingFileCanBeCleaned() {
        val directory = temporary.newFolder().toPath()
        val target = directory.resolve("target.xlsx")
        val neighbor = directory.resolve("neighbor.xlsx")
        Files.writeString(target, "delete")
        Files.writeString(neighbor, "keep")

        DesktopExportFiles.delete(sampleDesktopExport(target), directory, directory)
        DesktopExportFiles.delete(sampleDesktopExport(target), directory, directory)

        assertFalse(Files.exists(target))
        assertEquals("keep", Files.readString(neighbor))
    }

    @Test fun boundaryAndNonXlsxDeletionAreRefusedWithoutTouchingFiles() {
        val directory = temporary.newFolder().toPath()
        val outside = File(temporary.root, "outside.xlsx").toPath()
        val pdf = directory.resolve("source.pdf")
        Files.writeString(outside, "outside")
        Files.writeString(pdf, "%PDF-")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopExportFiles.delete(sampleDesktopExport(outside), directory, directory)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopExportFiles.delete(sampleDesktopExport(pdf), directory, directory)
        }
        assertTrue(Files.exists(outside))
        assertTrue(Files.exists(pdf))
    }
}

class DesktopControllerEditsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun editAndBatchDeletionAreDurableAndEachOperationHasOneUndoStep() = runBlocking {
        val root = temporary.newFolder().toPath()
        val result = sampleDesktopResult()
        DesktopStore(root).saveWorkspace(PocketData(results = listOf(result)))
        val controller = DesktopController(root)
        try {
            withTimeout(10_000) { controller.awaitIdle() }
            controller.editRecord("device-1", "1KM09")
            withTimeout(10_000) { controller.awaitIdle() }
            assertEquals("1KM09", controller.state.value.selectedResult!!.records.single { it.id == "device-1" }.text)
            assertTrue(controller.state.value.canUndo)
            assertEquals("1KM09", DesktopStore(root).load().data.results.single().records.single { it.id == "device-1" }.text)

            controller.deleteRecords(setOf("device-1", "device-2"))
            withTimeout(10_000) { controller.awaitIdle() }
            assertTrue(controller.state.value.selectedResult!!.records.none { it.kind == RecordKind.LABEL })
            controller.undo()
            withTimeout(10_000) { controller.awaitIdle() }
            assertEquals(2, controller.state.value.selectedResult!!.records.count { it.kind == RecordKind.LABEL })
            assertEquals("1KM09", controller.state.value.selectedResult!!.records.single { it.id == "device-1" }.text)
            controller.undo()
            withTimeout(10_000) { controller.awaitIdle() }
            assertEquals(result, DesktopStore(root).load().data.results.single())
            assertFalse(controller.state.value.canUndo)
        } finally {
            controller.close()
        }
    }

    @Test fun failedSaveDoesNotPublishEditedResultOrCreateUndoSuccess() = runBlocking {
        val root = temporary.newFolder().toPath()
        val result = sampleDesktopResult()
        DesktopStore(root).saveWorkspace(PocketData(results = listOf(result)))
        val controller = DesktopController(root)
        try {
            withTimeout(10_000) { controller.awaitIdle() }
            Files.delete(root.resolve("workspace.json"))
            Files.createDirectory(root.resolve("workspace.json"))
            Files.writeString(root.resolve("workspace.json/occupied"), "keep")

            controller.editRecord("device-1", "1KM09")
            withTimeout(10_000) { controller.awaitIdle() }

            assertEquals(result, controller.state.value.selectedResult)
            assertFalse(controller.state.value.canUndo)
            assertNotNull(controller.state.value.message)
        } finally {
            controller.close()
        }
    }

    @Test fun addedLabelCanBeUndoneAndDirectExportsAreImmediatelyIndexedWithoutOverwrite() = runBlocking {
        val root = temporary.newFolder().toPath()
        val result = sampleDesktopResult()
        DesktopStore(root).saveWorkspace(PocketData(results = listOf(result)))
        val controller = DesktopController(root)
        try {
            withTimeout(10_000) { controller.awaitIdle() }
            controller.addRecord(LabelColumn.DEVICE, "C01", "1KM03")
            withTimeout(10_000) { controller.awaitIdle() }
            assertTrue(controller.state.value.selectedResult!!.records.any { it.text == "1KM03" && it.edited })
            controller.undo()
            withTimeout(10_000) { controller.awaitIdle() }
            assertEquals(result, controller.state.value.selectedResult)

            controller.exportCurrent()
            withTimeout(10_000) { controller.awaitIdle() }
            assertEquals(1, controller.state.value.data.exports.size)
            assertTrue(Files.isRegularFile(Path.of(controller.state.value.data.exports.single().uri)))
            controller.exportCurrent()
            withTimeout(10_000) { controller.awaitIdle() }
            val exports = controller.state.value.data.exports
            assertEquals(2, exports.size)
            assertEquals(2, exports.map { it.uri }.toSet().size)
            assertEquals(exports, DesktopStore(root).load().data.exports)
        } finally {
            controller.close()
        }
    }
}

private fun sampleDesktopResult(documentId: String = "document-1"): ExtractionResult = ExtractionResult(
    id = "result-1", documentId = documentId, documentName = "真实机台.pdf", mode = ExtractionMode.DEVICE,
    createdAt = 12345,
    records = listOf(
        LabelRecord("group", LabelColumn.DEVICE, "C01", "C01", kind = RecordKind.GROUP),
        LabelRecord("filename", LabelColumn.DEVICE, "C01", "真实机台.pdf", kind = RecordKind.FILE_LABEL),
        LabelRecord("device-1", LabelColumn.DEVICE, "C01", "1KM01", 2, 12f, 34f, 6f, 8f,
            sourceText = "-1KM01", installationId = "physical-one", deviceId = "1KM01", evidence = "原生安装位置"),
        LabelRecord("device-2", LabelColumn.DEVICE, "C01", "1KM02", 2, 24f, 34f, 6f, 8f,
            sourceText = "-1KM02", installationId = "physical-two", deviceId = "1KM02", evidence = "原生安装位置"),
    ),
    issues = listOf(ExtractionIssue("来源说明", 2, "C01")), pageCount = 3,
    rules = RuleSettings(defaultCn1Cores = 8),
)

private fun sampleDesktopExport(path: Path): ExportedFile = ExportedFile(
    id = UUID.randomUUID().toString(), resultId = "result-1", documentName = "真实机台.pdf",
    mode = ExtractionMode.DEVICE, name = path.fileName.toString(), uri = path.toAbsolutePath().toString(),
    createdAt = 12345, bytes = 100, completed = true,
)
