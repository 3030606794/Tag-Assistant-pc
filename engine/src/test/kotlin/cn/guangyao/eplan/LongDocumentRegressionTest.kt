package cn.guangyao.eplan

import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.DeflaterOutputStream

/** The reported 59 MiB drawing has 4,023 pages and 1,814,657 xref slots. File size alone
 * does not exercise opening memory: these live compressed metadata objects do. Cloud only. */
class LongDocumentRegressionTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root

    @Test fun fourThousandPagesAndEighteenHundredThousandObjectsKeepCompactIndexAndReadablePages() {

        val file = fixture(File(cacheDirectory, "long-${UUID.randomUUID()}.pdf"))
        try {
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                assertEquals(4023, session.pdf.numberOfPages)
                assertTrue(session.pdf.document.xrefTable.size > 1_800_000)
                assertTrue("Index must use primitive blocks, not one boxed object per entry", session.xrefIndexBytes() in 1..16L * 1024 * 1024)
                assertTrue("Unused compressed objects stay unmaterialized", session.retainedObjectCount() < 4100)
                for (page in listOf(1, 2013, 4023, 1)) {
                    val pdf = session.preparePage(page)
                    val text = PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf)
                    assertTrue(text.contains("LONG-DOCUMENT-OK"))
                    session.releasePage()
                    assertTrue(session.retainedObjectCount() < 4100)
                }
                println("LONG_DOCUMENT_PROOF pages=4023 xref=${session.pdf.document.xrefTable.size} indexBytes=${session.xrefIndexBytes()} retained=${session.retainedObjectCount()} pdfBytes=${file.length()}")
            }
        } finally { file.delete() }
    }

    private fun fixture(file: File): File {
        val pageCount = 4023
        val firstMetadata = 10_000
        val metadataCount = 1_800_000
        val perStream = 1000
        val firstStream = firstMetadata + metadataCount
        val streamCount = metadataCount / perStream
        val xrefId = firstStream + streamCount
        RandomAccessFile(file, "rw").use { out ->
            val offsets = HashMap<Int, Long>()
            fun text(value: String) = out.write(value.toByteArray(Charsets.ISO_8859_1))
            fun obj(id: Int, value: String) {
                offsets[id] = out.filePointer
                text("$id 0 obj\n$value\nendobj\n")
            }
            fun stream(id: Int, dictionary: String, data: ByteArray) {
                offsets[id] = out.filePointer
                text("$id 0 obj\n<< $dictionary /Length ${data.size} >>\nstream\n")
                out.write(data)
                text("\nendstream\nendobj\n")
            }
            text("%PDF-1.7\n")
            obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            obj(2, "<< /Type /Pages /Count $pageCount /Kids [${(0 until pageCount).joinToString(" ") { "${100 + it} 0 R" }}] /MediaBox [0 0 400 600] /Resources << /Font << /F1 4 0 R >> >> >>")
            obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            stream(5, "", "BT /F1 12 Tf 30 520 Td (LONG-DOCUMENT-OK) Tj ET\n".toByteArray())
            repeat(pageCount) { obj(100 + it, "<< /Type /Page /Parent 2 0 R /Contents 5 0 R >>") }
            repeat(streamCount) { block ->
                // Every xref type-2 entry describes a real object in an actual object stream.
                val head = StringBuilder()
                val body = StringBuilder()
                repeat(perStream) { item ->
                    val id = firstMetadata + block * perStream + item
                    head.append(id).append(' ').append(body.length).append(' ')
                    body.append("<< /Type /Action /S /GoTo /D [100 0 R /Fit] >>\n")
                }
                val compressed = ByteArrayOutputStream()
                DeflaterOutputStream(compressed).use { it.write((head.toString() + body).toByteArray(Charsets.ISO_8859_1)) }
                stream(firstStream + block, "/Type /ObjStm /N $perStream /First ${head.length} /Filter /FlateDecode", compressed.toByteArray())
            }
            val xrefOffset = out.filePointer
            offsets[xrefId] = xrefOffset
            val compressed = ByteArrayOutputStream()
            DataOutputStream(DeflaterOutputStream(compressed)).use { records ->
                for (id in 0..xrefId) {
                    when {
                        id in firstMetadata until firstStream -> {
                            records.writeByte(2)
                            records.writeInt(firstStream + (id - firstMetadata) / perStream)
                            records.writeShort((id - firstMetadata) % perStream)
                        }
                        offsets.containsKey(id) -> {
                            records.writeByte(1); records.writeInt(offsets.getValue(id).toInt()); records.writeShort(0)
                        }
                        else -> { records.writeByte(0); records.writeInt(0); records.writeShort(65535) }
                    }
                }
            }
            stream(xrefId, "/Type /XRef /Size ${xrefId + 1} /Root 1 0 R /W [1 4 2] /Filter /FlateDecode", compressed.toByteArray())
            text("startxref\n$xrefOffset\n%%EOF\n")
        }
        return file
    }
}
