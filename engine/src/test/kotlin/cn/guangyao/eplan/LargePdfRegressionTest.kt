package cn.guangyao.eplan

import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Cloud JVM tests, run with a 512 MiB heap. The large fixture is a valid sparse raw-pixel image
 * stream referenced by both real pages; it is not trailing junk or an empty renamed file. */
class LargePdfRegressionTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    @Test fun extracts999MiBImagePdfWithHeapSmallerThanFileAndReloadsSharedResources() {

        val file = fixture(imageBytes = 999L * 1024 * 1024)
        try {
            assertTrue(file.length() > 999L * 1024 * 1024)
            assertTrue(file.length() < 1000L * 1024 * 1024)
            assertTrue("CI regression must use a bounded heap smaller than the PDF", Runtime.getRuntime().maxMemory() < file.length())
            val imported = ImportedDocument(UUID.randomUUID().toString(), "B06B07-1.pdf", file.path, file.length(), 0)
            val result = OfflinePdfEngine(cacheDirectory).extract(imported, ExtractionMode.DEVICE, RuleSettings(), {}, { false })
            assertTrue(result.completed)
            assertEquals(2, result.pageCount)
            assertEquals(listOf("UM01", "UM02"), result.records.filter { it.kind == RecordKind.LABEL }.map { it.text })
            assertEquals(listOf("B06B07-1.pdf"), result.records.filter { it.kind == RecordKind.FILE_LABEL }.map { it.text })
            println("LARGE_PDF_PROOF bytes=${file.length()} maxHeap=${Runtime.getRuntime().maxMemory()} pages=${result.pageCount} labels=${result.records.filter { it.kind == RecordKind.LABEL }.map { it.text }}")
        } finally { file.delete() }
    }

    @Test fun hundredThousandDeviceBookmarksStayUnparsedWhilePageIndexAndTextRemainAvailable() {

        val file = fixture(deviceBookmarks = 100_000)
        try {
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                assertEquals(2, session.pdf.numberOfPages)
                assertTrue("Only structural page metadata may remain", session.retainedObjectCount() < 40)
                println("OUTLINE_PROOF deviceBookmarks=100000 retainedObjects=${session.retainedObjectCount()}")
                repeat(4) { index ->
                    val page = index % 2 + 1
                    val pdf = session.preparePage(page)
                    val text = PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf)
                    assertTrue(text.contains("-UM0$page"))
                    session.releasePage()
                    assertTrue("Page resources must be released, not retained across all pages", session.retainedObjectCount() < 40)
                }
            }
        } finally { file.delete() }
    }

    @Test fun cancellationDuringOpeningClosesResourcesAndAllowsRetry() {

        val file = fixture(deviceBookmarks = 100)
        try {
            var calls = 0
            var cancelled = false
            try { IncrementalPdfDocument(file, cacheDirectory) { ++calls > 4 }.close() }
            catch (_: IncrementalPdfDocument.CancelledException) { cancelled = true }
            assertTrue(cancelled)
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { assertEquals(2, it.pdf.numberOfPages) }
        } finally { file.delete() }
    }

    @Test fun compressedPagesSharedFormFontAndToUnicodeSurviveRepeatedRelease() {

        val file = compressedFixture()
        try {
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val firstPageDictionary = session.pdf.getPage(0).cosObject
                repeat(6) { index ->
                    val page = index % 2 + 1
                    val pdf = session.preparePage(page)
                    val text = PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf)
                    assertTrue("Shared Form and ToUnicode text must reload on every page", text.contains("-UM07"))
                    assertTrue("Re-reading font's object stream must preserve pinned page identity", firstPageDictionary === session.pdf.getPage(0).cosObject)
                    session.releasePage()
                }
            }
        } finally { file.delete() }
    }

    @Test fun safetyLimitInsideNestedFormCannotBecomeSuccessfulPartialPageCache() {

        val file = compressedFixture("q\n".repeat(300) + "BT /F1 12 Tf 30 520 Td (-X) Tj ET\n")
        val document = ImportedDocument(UUID.randomUUID().toString(), "complex.pdf", file.path, file.length(), 0)
        try {
            val failure = assertThrows(IOException::class.java) {
                OfflinePdfEngine(cacheDirectory).extract(document, ExtractionMode.DEVICE, RuleSettings(), {}, { false })
            }
            assertTrue(failure.message.orEmpty().contains("嵌套过深"))
            val caches = File(cacheDirectory, "offline-pdf").listFiles().orEmpty().filter { it.name.startsWith(document.id) }
            assertFalse(caches.any { File(it, "1.json").exists() })
        } finally { file.delete() }
    }

    private fun compressedFixture(formBody: String = "BT /F1 12 Tf 30 520 Td (-X) Tj ET\n"): File {
        val file = File(cacheDirectory, "compressed-${UUID.randomUUID()}.pdf")
        RandomAccessFile(file, "rw").use { out ->
            val offsets = mutableMapOf<Int, Long>()
            fun bytes(text: String) = text.toByteArray(StandardCharsets.ISO_8859_1)
            fun text(text: String) = out.write(bytes(text))
            fun obj(id: Int, body: String) {
                offsets[id] = out.filePointer
                text("$id 0 obj\n$body\nendobj\n")
            }
            fun stream(id: Int, body: String, dictionary: String = "") = obj(id, "<< /Length ${bytes(body).size} $dictionary >>\nstream\n$body\nendstream")
            text("%PDF-1.7\n")
            val members = linkedMapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>",
                2 to "<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] /MediaBox [0 0 400 600] /Resources << /XObject << /Fm0 6 0 R >> >> >>",
                3 to "<< /Type /Page /Parent 2 0 R /Contents 5 0 R >>",
                4 to "<< /Type /Page /Parent 2 0 R /Contents 7 0 R >>",
                8 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 9 0 R >>",
            )
            val data = StringBuilder()
            val header = StringBuilder()
            members.forEach { (id, body) -> header.append("$id ${data.length} "); data.append(body).append('\n') }
            stream(10, header.toString() + data, "/Type /ObjStm /N ${members.size} /First ${header.length}")
            stream(5, "q /Fm0 Do Q\n")
            stream(7, "q 1 0 0 1 20 0 cm /Fm0 Do Q\n")
            stream(6, formBody, "/Type /XObject /Subtype /Form /BBox [0 0 400 600] /Resources << /Font << /F1 8 0 R >> >>")
            stream(9, "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /EplanTest def\n/CMapType 2 def\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n2 beginbfchar\n<2D> <002D>\n<58> <0055004D00300037>\nendbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
            val xref = out.filePointer
            offsets[11] = xref
            val binary = ByteArrayOutputStream()
            DataOutputStream(binary).use { records ->
                for (id in 0..11) {
                    val compressedIndex = members.keys.indexOf(id)
                    when {
                        compressedIndex >= 0 -> { records.writeByte(2); records.writeLong(10); records.writeShort(compressedIndex) }
                        offsets[id] != null -> { records.writeByte(1); records.writeLong(offsets.getValue(id)); records.writeShort(0) }
                        else -> { records.writeByte(0); records.writeLong(0); records.writeShort(65535) }
                    }
                }
            }
            text("11 0 obj\n<< /Type /XRef /Size 12 /Root 1 0 R /W [1 8 2] /Length ${binary.size()} >>\nstream\n")
            out.write(binary.toByteArray())
            text("\nendstream\nendobj\nstartxref\n$xref\n%%EOF\n")
        }
        return file
    }

    private fun fixture(imageBytes: Long = 0L, deviceBookmarks: Int = 0): File {
        val file = File(cacheDirectory, "large-${UUID.randomUUID()}.pdf")
        RandomAccessFile(file, "rw").use { out ->
            val offsets = linkedMapOf<Int, Long>()
            fun text(value: String) = out.write(value.toByteArray(StandardCharsets.ISO_8859_1))
            fun obj(id: Int, value: String) {
                offsets[id] = out.filePointer
                text("$id 0 obj\n$value\nendobj\n")
            }
            fun stream(id: Int, value: String) = obj(id, "<< /Length ${value.length} >>\nstream\n$value\nendstream")
            text("%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n")
            obj(1, "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>")
            obj(2, "<< /Type /Pages /Count 2 /Kids [3 0 R 5 0 R] /MediaBox [0 0 400 600] /Resources << /Font << /F1 4 0 R >> ${if (imageBytes > 0) "/XObject << /Im0 7 0 R >>" else ""} >> >>")
            obj(3, "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>")
            obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            obj(5, "<< /Type /Page /Parent 2 0 R /Contents 10 0 R >>")
            val drawImage = if (imageBytes > 0) "q 10 0 0 10 0 0 cm /Im0 Do Q\n" else ""
            stream(6, "${drawImage}BT /F1 12 Tf 30 520 Td (-UM01) Tj ET\n")
            if (imageBytes > 0) {
                offsets[7] = out.filePointer
                text("7 0 obj\n<< /Type /XObject /Subtype /Image /Width 1024 /Height ${imageBytes / 1024} /ColorSpace /DeviceGray /BitsPerComponent 8 /Length $imageBytes >>\nstream\n")
                // Holes are real zero-valued pixels, legal in an unfiltered grayscale image stream.
                out.seek(out.filePointer + imageBytes)
                text("\nendstream\nendobj\n")
            }
            stream(10, "${drawImage}BT /F1 12 Tf 30 520 Td (-UM02) Tj ET\n")
            obj(8, "<< /Type /Outlines /First 12 0 R /Last ${if (deviceBookmarks > 0) 20 else 12} 0 R >>")
            obj(12, "<< /Title (=TEST) /Parent 8 0 R /First 13 0 R /Last 13 0 R ${if (deviceBookmarks > 0) "/Next 20 0 R" else ""} >>")
            obj(13, "<< /Title (+BOX) /Parent 12 0 R /First 14 0 R /Last 14 0 R >>")
            obj(14, "<< /Title (&ELU1) /Parent 13 0 R /First 15 0 R /Last 16 0 R >>")
            obj(15, "<< /Title (1 Main cabinet) /Parent 14 0 R /Next 16 0 R /Dest [3 0 R /Fit] >>")
            obj(16, "<< /Title (2 Main cabinet) /Parent 14 0 R /Prev 15 0 R /Dest [5 0 R /Fit] >>")
            if (deviceBookmarks > 0) {
                obj(20, "<< /Title (Devices) /Parent 8 0 R /First 21 0 R /Last ${20 + deviceBookmarks} 0 R >>")
                repeat(deviceBookmarks) { index ->
                    val id = 21 + index
                    obj(id, "<< /Title (Unused device $index) /Parent 20 0 R /Dest [3 0 R /Fit] ${if (index + 1 < deviceBookmarks) "/Next ${id + 1} 0 R" else ""} >>")
                }
            }
            val start = out.filePointer
            val count = offsets.keys.max() + 1
            text("xref\n0 $count\n0000000000 65535 f \n")
            for (id in 1 until count) {
                val offset = offsets[id]
                text(if (offset == null) "0000000000 00000 f \n" else "%010d 00000 n \n".format(java.util.Locale.ROOT, offset))
            }
            text("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
        }
        return file
    }
}
