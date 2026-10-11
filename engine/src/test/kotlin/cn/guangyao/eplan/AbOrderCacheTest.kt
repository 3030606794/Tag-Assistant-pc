package cn.guangyao.eplan

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AbOrderCacheTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root

    private val boxes = listOf(PdfTextBox("-1XT01", 10f, 15f, 9f, 3f), PdfTextBox("-1QF01", 80f, 15f, 9f, 3f))
    private val page = PdfPageText(1, 120f, 85f, boxes)

    @Test fun onlyCompleteConfidentPermutationsAreCachedWithTheOriginalSourceBoxes() {
        withCache { cache, _ ->
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(listOf(AbInstalledPosition(boxes[0], 0, 90)), true))
            assertNull(cache.read(page, boxes, null))
            val positions = boxes.reversed().map { AbInstalledPosition(it, 0, 90) }
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(positions, false))
            assertNull(cache.read(page, boxes, null))
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(positions, true))
            assertEquals(positions, cache.read(page, boxes, null)!!.positions)
            assertNull(cache.read(page, boxes, 270))
        }
    }

    @Test fun changedCoordinatesOrIdentifiersCannotReuseAnOldInstallationOrder() {
        withCache { cache, _ ->
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(boxes.map { AbInstalledPosition(it, 0, 0) }, true))
            assertNull(cache.read(page, listOf(boxes[0].copy(x = 11f), boxes[1]), null))
            assertNull(cache.read(page, listOf(boxes[0].copy(text = "-1XT02"), boxes[1]), null))
            assertNull(cache.read(page.copy(width = 240f), boxes, null))
        }
    }

    @Test fun updatedGeometryAtomicallyReplacesAnExistingWindowsCache() {
        withCache { cache, directory ->
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(boxes.map { AbInstalledPosition(it, 0, 0) }, true))
            val moved = boxes.map { it.copy(x = it.x + 3f) }
            val reordered = moved.reversed().map { AbInstalledPosition(it, 1, 90) }
            cache.write(page, moved, null, AbInstallationOrder.OrderResult(reordered, true))
            assertEquals(reordered, cache.read(page, moved, null)!!.positions)
            assertNull(cache.read(page, boxes, null))
            assertFalse(File(directory, "ab-order-v4-1-auto.json.tmp").exists())
        }
    }

    @Test fun corruptOrDuplicateIndicesAreRejectedInsteadOfLosingRealLabels() {
        withCache { cache, directory ->
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(boxes.map { AbInstalledPosition(it, 0, 90) }, true))
            val file = File(directory, "ab-order-v4-1-auto.json")
            val data = org.json.JSONObject(file.readText())
            data.getJSONArray("positions").getJSONArray(1).put(0, 0)
            file.writeText(data.toString())
            assertNull(cache.read(page, boxes, null))
            file.writeText("{interrupted")
            assertNull(cache.read(page, boxes, null))
        }
    }

    @Test fun anOlderGeometryCacheIsIgnoredAndReplacedWithoutDeletingTheDocument() {
        withCache { cache, directory ->
            val positions = boxes.map { AbInstalledPosition(it, 0, 90) }
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(positions, true))
            val current = File(directory, "ab-order-v4-1-auto.json")
            val legacy = File(directory, "ab-order-v3-1-auto.json")
            assertTrue(current.renameTo(legacy))
            assertNull(cache.read(page, boxes, null))
            cache.write(page, boxes, null, AbInstallationOrder.OrderResult(positions, true))
            assertEquals(positions, cache.read(page, boxes, null)!!.positions)
            assertTrue(legacy.isFile)
        }
    }

    private fun withCache(block: (AbOrderCache, File) -> Unit) {

        val id = UUID.randomUUID().toString()
        val directory = File(cacheDirectory, "offline-pdf/$id-100-123-desktop-pdfbox2-v6")
        try { block(AbOrderCache(cacheDirectory, ImportedDocument(id, "test.pdf", "unused", 100, 0), 123), directory) }
        finally { directory.deleteRecursively() }
    }
}
