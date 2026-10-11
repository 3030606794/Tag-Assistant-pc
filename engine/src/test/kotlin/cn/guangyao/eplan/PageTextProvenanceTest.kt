package cn.guangyao.eplan

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Exercise the real Apache PDFBox decoder and disk cache in cloud CI, without a device. */
class PageTextProvenanceTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    @Test fun readerKeepsOverlappingOperatorsAndContinuousFontSegmentsDistinct() = withPdf("""
        0 0 1 rg
        BT /F1 10 Tf 20 300 Td (IO-) Tj /F2 10 Tf (LINK) Tj ET
        BT /F1 10 Tf 20 270 Td (1:START) Tj ET
        BT /F1 10 Tf 25 270 Td (2:ALARM) Tj ET
    """.trimIndent()) { pdf ->
        val page = read(pdf)
        val native = page.layoutBoxes!!
        assertEquals(listOf("IO-LINK", "1:START", "2:ALARM"), native.map { it.text })
        assertTrue(native.all { it.sourceRun >= 0 && it.sourceObject >= 0 })
        assertNotEquals(native[1].sourceRun, native[2].sourceRun)
        assertNotEquals(native[1].sourceObject, native[2].sourceObject)
        assertEquals(20f, native[1].baselineX, .001f)
        assertEquals(25f, native[2].baselineX, .001f)
        assertNotEquals(native.map { it.text }, page.boxes.map { it.text })
        assertTrue("Legacy boxes retain their previous metadata defaults", page.boxes.all { it.sourceRun == -1 })
    }

    @Test fun quoteOperatorsAndTJPositionChangesRetainTheirOwnNativeRuns() = withPdf("""
        BT /F1 10 Tf 12 TL 20 300 Td (ROW1) Tj (ROW2) ' 0 0 (ROW3) " ET
        BT /F1 10 Tf 20 240 Td [(LEFT) -5000 (RIGHT)] TJ ET
    """.trimIndent()) { pdf ->
        val native = read(pdf).layoutBoxes!!
        assertEquals(listOf("ROW1", "ROW2", "ROW3", "LEFT", "RIGHT"), native.map { it.text })
        // Both quote operators internally dispatch T* with null operands. They must move the
        // baseline without losing their surrounding BT or combining the independent lines.
        assertEquals(listOf(100f, 112f, 124f), native.take(3).map { it.baselineY })
        assertEquals(1, native.take(3).map { it.sourceObject }.distinct().size)
        assertEquals(3, native.take(3).map { it.sourceRun }.distinct().size)
        assertEquals("TJ's jump is split geometrically, despite its shared operation", native[3].sourceRun, native[4].sourceRun)
    }

    @Test fun directSecondPageSelectionUsesApacheOneBasedGateWithoutReadingTheFirstPage() = withPdf("""
        BT /F1 10 Tf 20 300 Td (FIRST) Tj ET
    """.trimIndent()) { pdf ->
        val second = PDPage(PDRectangle(400f, 400f)).apply {
            resources = pdf.getPage(0).resources
            setContents(PDStream(pdf, ByteArrayInputStream("BT /F1 10 Tf 30 250 Td (SECOND) Tj ET".toByteArray(Charsets.US_ASCII))))
        }
        pdf.addPage(second)
        val page = read(pdf, 2)
        val native = requireNotNull(page.layoutBoxes)
        assertEquals(2, page.page)
        assertEquals(listOf("SECOND"), native.map { it.text })
        assertEquals(listOf("SECOND"), page.boxes.map { it.text })
        assertEquals(30f, native.single().baselineX, .001f)
    }

    @Test fun cacheRoundTripPreservesBothTextViewsAndSourceIdentity() = withPdf("""
        BT /F1 10 Tf 20 300 Td (1:START) Tj ET
        BT /F1 10 Tf 25 300 Td (2:ALARM) Tj ET
    """.trimIndent()) { pdf ->
        pdf.getPage(0).rotation = 90
        val original = read(pdf)
        assertEquals(90, original.rotation)
        val imported = ImportedDocument(UUID.randomUUID().toString(), "provenance.pdf", "unused", 10, 0)
        val cacheClass = Class.forName("cn.guangyao.eplan.OfflinePdfEngine\$PageCache")
        val cache = cacheClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(cacheDirectory, imported, 123L)
        cacheClass.getDeclaredMethod("write", PdfPageText::class.java).apply { isAccessible = true }.invoke(cache, original)
        val restored = cacheClass.getDeclaredMethod("read", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(cache, 1) as PdfPageText?
        assertNotNull(restored)
        assertEquals(original, restored)
        val directory = File(cacheDirectory, "offline-pdf/${imported.id}-10-123-desktop-pdfbox2-v6")
        assertTrue(File(directory, "1.json").isFile)
        File(directory, "1.json").delete()
        directory.delete()
    }

    @Test fun cacheRejectsAFileMissingTheNativeViewInsteadOfReusingCorruptLegacyText() {
        val imported = ImportedDocument(UUID.randomUUID().toString(), "old.pdf", "unused", 10, 0)
        val cacheClass = Class.forName("cn.guangyao.eplan.OfflinePdfEngine\$PageCache")
        val cache = cacheClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(cacheDirectory, imported, 123L)
        val directory = File(cacheDirectory, "offline-pdf/${imported.id}-10-123-desktop-pdfbox2-v6")
        val file = File(directory, "1.json")
        try {
            file.writeText("""{"width":400,"height":400,"rotation":0,"boxes":[]}""")
            val restored = cacheClass.getDeclaredMethod("read", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(cache, 1)
            assertNull(restored)
        } finally { file.delete(); directory.delete() }
    }

    private fun read(pdf: PDDocument, page: Int = 1): PdfPageText {
        val readerClass = Class.forName("cn.guangyao.eplan.OfflinePdfEngine\$PositionedStripper")
        val reader = readerClass.declaredConstructors.single().apply { isAccessible = true }.newInstance({ false } as () -> Boolean)
        return readerClass.getDeclaredMethod("read", PDDocument::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(reader, pdf, page) as PdfPageText
    }

    private fun withPdf(content: String, action: (PDDocument) -> Unit) {

        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(400f, 400f))
            page.resources = PDResources().apply {
                put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
                put(COSName.getPDFName("F2"), PDType1Font.HELVETICA_BOLD)
            }
            page.setContents(PDStream(pdf, ByteArrayInputStream(content.toByteArray(Charsets.US_ASCII))))
            pdf.addPage(page)
            action(pdf)
        }
    }
}
