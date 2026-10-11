package cn.guangyao.eplan

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class DesktopPdfPreviewBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ordinaryNativeFixtureIsAllowedByAnIncrementalMetadataProbe() {
        val source = temporary.newFile("native-layout.pdf")
        requireNotNull(javaClass.getResourceAsStream("/fixtures/layout-order-native.pdf")).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val decision = inspect(source)
        assertTrue(decision.message, decision.allowed)
        assertEquals(3, decision.pageCount)
        assertNull(decision.message)
        assertTrue("Probe closes the source on Windows", source.delete())
    }

    @Test fun oversizedFilesReturnBeforeParsingOrCreatingScratchStorage() {
        val source = temporary.newFile("oversized.pdf")
        RandomAccessFile(source, "rw").use { it.setLength(DesktopPdfPreviewBudget.MAX_FILE_BYTES + 1) }
        val scratch = File(temporary.root, "not-created")
        // Deliberately not a valid PDF: a parser invocation would return a different failure.
        val decision = DesktopPdfPreviewBudget.inspectWithHeapBudget(source, scratch, { false }, { 512L * 1024 * 1024 })
        assertFalse(decision.allowed)
        assertTrue(decision.message.orEmpty().contains("128 MiB"))
        assertFalse(scratch.exists())
        assertTrue(source.delete())
    }

    @Test fun unknownPageCountAndLowerStructuralBudgetAreRejectedWithoutChangingExtractionDefaults() {
        val source = temporary.newFile("long-small.pdf")
        PDDocument().use { pdf ->
            repeat(DesktopPdfPreviewBudget.MAX_PAGES + 1) { pdf.addPage(PDPage()) }
            pdf.save(source)
        }
        assertTrue(source.length() < DesktopPdfPreviewBudget.MAX_FILE_BYTES)
        val decision = inspect(source)
        assertFalse(decision.allowed)
        assertEquals(1501, decision.pageCount)
        assertTrue(decision.message.orEmpty().contains("1500"))
        assertThrows(IOException::class.java) { IncrementalPdfDocument(source, temporary.root, { false }, 2).close() }
        IncrementalPdfDocument(source, temporary.root) { false }.use { session ->
            assertEquals("Default extraction keeps its original structural budget", 1501, session.pdf.numberOfPages)
        }
        assertTrue(source.delete())
    }

    private fun inspect(source: File) = DesktopPdfPreviewBudget.inspectWithHeapBudget(source, temporary.root,
        { false }, { 512L * 1024 * 1024 })
}
