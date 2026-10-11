package cn.guangyao.eplan

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.roundToInt

/** Real ordinary PDFBox renders verify the desktop fallback, not captured Android pixels. */
class DesktopRasterSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun croppedPagesAndAllQuarterTurnsMapToDisplayedSourceCoordinates() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val file = fixture(rotation)
            val raster = requireNotNull(DesktopRasterSource(temporary.root).read(imported(file), 1) {})
            val displayWidth = if (rotation % 180 == 0) 200f else 100f
            val displayHeight = if (rotation % 180 == 0) 100f else 200f
            assertEquals(if (rotation % 180 == 0) 1200 else 600, raster.width)
            assertEquals(if (rotation % 180 == 0) 600 else 1200, raster.height)
            for ((x, expected) in listOf(50f to Color.RED.rgb, 150f to Color.BLUE.rgb)) {
                val point = EplanParsing.rotate(x, 50f, 200f, 100f, rotation)
                val px = (point.first / displayWidth * raster.width).roundToInt().coerceIn(0, raster.width - 1)
                val py = (point.second / displayHeight * raster.height).roundToInt().coerceIn(0, raster.height - 1)
                assertEquals("rotation=$rotation, point=$point", expected, raster.pixels[py * raster.width + px])
            }
        }
    }

    @Test fun cancellationInsideDrawingClosesTheDocumentAndAllowsASecondRender() {
        val file = fixture(repeatedDrawing = true)
        var checks = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            DesktopRasterSource(temporary.root).read(imported(file), 1) {
                if (++checks == 3) error("cancel-drawing")
            }
        }
        assertEquals("cancel-drawing", failure.message)
        assertNotNull(DesktopRasterSource(temporary.root).read(imported(file), 1) {})
        assertTrue("Closed PDF sources can be deleted on Windows", file.delete())
    }

    @Test fun oversizedUnsupportedFallbackIsSkippedBeforeOpeningItsContents() {
        val file = temporary.newFile("large-unsupported.pdf")
        RandomAccessFile(file, "rw").use { it.setLength(257L * 1024 * 1024) }
        assertNull(DesktopRasterSource(temporary.root).read(imported(file), 1) {})
        assertTrue(file.delete())
    }

    @Test fun unavailablePageFailsAndReleasesItsSourceForRetry() {
        val file = fixture()
        assertThrows(IOException::class.java) { DesktopRasterSource(temporary.root).read(imported(file), 2) {} }
        assertNotNull(DesktopRasterSource(temporary.root).read(imported(file), 1) {})
        assertTrue(file.delete())
    }

    private fun fixture(rotation: Int = 0, repeatedDrawing: Boolean = false): File {
        val file = temporary.newFile("render-${UUID.randomUUID()}.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(260f, 160f)).apply {
                cropBox = PDRectangle(20f, 30f, 200f, 100f)
                this.rotation = rotation
            }
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.setNonStrokingColor(Color.RED)
                stream.addRect(20f, 30f, 100f, 100f); stream.fill()
                stream.setNonStrokingColor(Color.BLUE)
                stream.addRect(120f, 30f, 100f, 100f); stream.fill()
                if (repeatedDrawing) repeat(500) { stream.addRect(20f, 30f, 1f, 1f); stream.fill() }
            }
            pdf.save(file)
        }
        return file
    }

    private fun imported(file: File) = ImportedDocument(UUID.randomUUID().toString(), file.name, file.absolutePath, file.length(), 0)
}
