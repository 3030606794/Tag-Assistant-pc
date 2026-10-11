package cn.guangyao.eplan

import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.DeflaterOutputStream

/** Synthetic PDF regressions for cloud CI; never upload customer drawings as fixtures. */
class IncrementalPdfLinksTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root



    @Test fun readsIndirectEplanLiteralAndRetainsOnlyStructuralObjectsAfterRelease() {
        withFixture { file ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val retained = session.retainedObjectCount()
                repeat(4) {
                    val links = session.readPageLinks(1)
                    assertEquals(1, links.size)
                    val link = links.single()
                    assertEquals(2, link.targetPage)
                    assertRect(link.source, 30f, 60f, 50f, 20f)
                    assertRect(link.target!!, 100f, 350f, 50f, 50f)
                    session.releasePage()
                    assertEquals("Annotations/actions/scripts must not accumulate", retained, session.retainedObjectCount())
                    // Link data remains usable after all transient PDF objects have been released.
                    assertEquals(2, link.targetPage)
                    assertRect(link.target!!, 100f, 350f, 50f, 50f)
                }
            }
        }
    }

    @Test fun onlyWholeNumericHighlightScriptsAreAcceptedAndDestinationRemainsUsable() {
        val rejected = listOf(
            "highlight(1, [100, 200, 150, 250]); app.alert('x');",
            "highlight(1, [100 + 2, 200, 150, 250]);",
            "evil.highlight(1, [100, 200, 150, 250]);",
            "highlight(0, [100, 200, 150, 250]);", // Must agree with GoTo's page.
            "highlight(1, [NaN, 200, 150, 250]);",
            "highlight(1, [1e100, 200, 150, 250]);",
            "highlight(1, [100, 200, 150, 250]);" + " ".repeat(9000),
        )
        rejected.forEach { script ->
            withFixture(script = script) { file ->
                IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                    val link = session.readPageLinks(1).single()
                    assertEquals(2, link.targetPage)
                    assertNull(script.take(80), link.target)
                }
            }
        }
    }

    @Test fun fitRProvidesRectangleButXYZDoesNotInventOneAndExternalActionsAreIgnored() {
        withFixture(
            annotation = "<< /Subtype /Link /Rect [30 520 80 540] /Dest [4 0 R /FitR 100 200 150 250] >>",
        ) { file ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                assertRect(session.readPageLinks(1).single().target!!, 100f, 350f, 50f, 50f)
            }
        }
        withFixture(
            annotation = "<< /Subtype /Link /Rect [30 520 80 540] /Dest [4 0 R /XYZ 100 200 null] >>",
        ) { file ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val link = session.readPageLinks(1).single()
                assertEquals(2, link.targetPage)
                assertNull(link.target)
            }
        }
        listOf(
            "<< /S /URI /URI (https://example.invalid) >>",
            "<< /S /GoToR /F (other.pdf) /D [0 /Fit] >>",
            "<< /S /Launch /F (program.exe) >>",
            "<< /S /JavaScript /JS (highlight\\(1, [100,200,150,250]\\);) >>",
        ).forEach { action ->
            withFixture(action = action) { file ->
                IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                    assertTrue(session.readPageLinks(1).isEmpty())
                }
            }
        }
    }

    @Test fun cropAndAllPageRotationsMatchTextPositionCoordinates() {
        val sourceRects = mapOf(
            0 to floatArrayOf(20f, 80f, 50f, 20f),
            90 to floatArrayOf(500f, 20f, 20f, 50f),
            180 to floatArrayOf(330f, 500f, 50f, 20f),
            270 to floatArrayOf(80f, 330f, 20f, 50f),
        )
        sourceRects.forEach { (rotation, expected) ->
            withFixture(
                firstPageGeometry = "/MediaBox [0 0 500 700] /CropBox [10 20 410 620] /Rotate $rotation",
                secondPageGeometry = "/MediaBox [0 0 600 800] /CropBox [100 100 500 700] /Rotate 270",
                script = "highlight(1,[120,150,160,190]);",
            ) { file ->
                IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                    val link = session.readPageLinks(1).single()
                    assertRect(link.source, expected[0], expected[1], expected[2], expected[3])
                    // highlight arguments are already in Acrobat's rotated crop coordinates.
                    assertRect(link.target!!, 120f, 210f, 40f, 40f)
                    var textX = Float.NaN
                    var textY = Float.NaN
                    val stripper = object : PDFTextStripper() {
                        override fun processTextPosition(text: TextPosition) {
                            textX = text.x
                            textY = text.y
                        }
                    }.apply { startPage = 1; endPage = 1 }
                    stripper.getText(session.preparePage(1))
                    assertTrue("Link and native text must share display coordinates", textX in link.source.x..(link.source.x + link.source.width))
                    assertTrue("Link and native text must share display coordinates", textY in link.source.y..(link.source.y + link.source.height))
                }
            }
        }
    }

    @Test fun compressedScriptHasDecodedSizeLimit() {
        withFixture(scriptStream = true) { file ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                assertRect(session.readPageLinks(1).single().target!!, 100f, 350f, 50f, 50f)
            }
        }
        withFixture(scriptStream = true, script = " ".repeat(100_000) + "highlight(1,[100,200,150,250]);") { file ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                assertNull(session.readPageLinks(1).single().target)
            }
        }
    }

    @Test fun malformedAnnotationAndCyclicActionDoNotDiscardOtherLinks() {
        withFixture(
            extraObjects = mapOf(
                15 to "<< /Subtype /Link /Rect [1 2 3] /Dest [4 0 R /Fit] >>",
                16 to "<< /S /JavaScript /JS (unsupported) /Next 16 0 R >>",
            ),
            annotations = "[15 0 R 8 0 R]",
            action = "<< /S /GoTo /D 11 0 R /Next [16 0 R 12 0 R] >>",
        ) { file ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val links = session.readPageLinks(1)
                assertEquals(1, links.size)
                assertRect(links.single().target!!, 100f, 350f, 50f, 50f)
            }
        }
    }

    @Test fun cancellationAndAnnotationQuotaPropagateAndCleanupAllowsRetry() {
        withFixture { file ->
            var cancelled = false
            IncrementalPdfDocument(file, cacheDirectory) { cancelled }.use { session ->
                cancelled = true
                assertThrows(IncrementalPdfDocument.CancelledException::class.java) { session.readPageLinks(1) }
                cancelled = false
                assertEquals(1, session.readPageLinks(1).size)
                session.releasePage()
            }
        }
        withFixture(annotations = "[" + "8 0 R ".repeat(100) + "]") { file ->
            var readingLinks = false
            var reads = 0
            IncrementalPdfDocument(file, cacheDirectory) { readingLinks && ++reads > 40 }.use { session ->
                val retained = session.retainedObjectCount()
                readingLinks = true
                assertThrows(IncrementalPdfDocument.CancelledException::class.java) { session.readPageLinks(1) }
                readingLinks = false
                session.releasePage()
                assertEquals(retained, session.retainedObjectCount())
                assertEquals(100, session.readPageLinks(1).size)
            }
        }
        withFixture(annotations = "[" + "8 0 R ".repeat(10_001) + "]") { file ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val retained = session.retainedObjectCount()
                val failure = assertThrows(IOException::class.java) { session.readPageLinks(1) }
                assertTrue(failure.message.orEmpty().contains("链接过于复杂"))
                session.releasePage()
                assertEquals(retained, session.retainedObjectCount())
                val text = PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(session.preparePage(1))
                assertTrue("Optional links must not prevent native text extraction", text.contains("X"))
            }
        }
    }

    private fun assertRect(rect: IncrementalPdfDocument.Rect, x: Float, y: Float, width: Float, height: Float) {
        assertEquals(x, rect.x, .01f)
        assertEquals(y, rect.y, .01f)
        assertEquals(width, rect.width, .01f)
        assertEquals(height, rect.height, .01f)
    }

    private fun withFixture(
        script: String = "highlight(1, [100, 200, 150, 250]);\n",
        scriptStream: Boolean = false,
        annotation: String = "<< /Type /Annot /Subtype /Link /Rect 9 0 R /A 10 0 R >>",
        annotations: String = "[8 0 R]",
        action: String = "<< /S /GoTo /D 11 0 R /Next 12 0 R >>",
        firstPageGeometry: String = "",
        secondPageGeometry: String = "",
        extraObjects: Map<Int, String> = emptyMap(),
        block: (File) -> Unit,
    ) {

        val file = File(cacheDirectory, "links-${UUID.randomUUID()}.pdf")
        fun bytes(text: String) = text.toByteArray(StandardCharsets.ISO_8859_1)
        val objects = linkedMapOf<Int, ByteArray>()
        fun obj(number: Int, text: String) { objects[number] = bytes(text) }
        fun stream(number: Int, content: ByteArray, filters: String = "") {
            objects[number] = bytes("<< /Length ${content.size} $filters >>\nstream\n") + content + bytes("\nendstream")
        }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] /MediaBox [0 0 400 600] /Resources << /Font << /F1 6 0 R >> >> >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /Contents 5 0 R /Annots 7 0 R $firstPageGeometry >>")
        obj(4, "<< /Type /Page /Parent 2 0 R $secondPageGeometry >>")
        stream(5, bytes("BT /F1 8 Tf 40 530 Td (X) Tj ET\n"))
        obj(6, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        obj(7, annotations)
        obj(8, annotation)
        obj(9, "[30 520 80 540]")
        obj(10, action)
        obj(11, "[4 0 R /Fit]")
        obj(12, "<< /S /JavaScript /JS 13 0 R >>")
        if (scriptStream) {
            val output = ByteArrayOutputStream()
            DeflaterOutputStream(output).use { it.write(bytes(script)) }
            stream(13, output.toByteArray(), "/Filter /FlateDecode")
        } else {
            obj(13, "(" + script.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")")
        }
        extraObjects.forEach { (number, text) -> obj(number, text) }
        try {
            RandomAccessFile(file, "rw").use { out ->
                out.write(bytes("%PDF-1.7\n"))
                val offsets = mutableMapOf<Int, Long>()
                objects.forEach { (number, body) ->
                    offsets[number] = out.filePointer
                    out.write(bytes("$number 0 obj\n"))
                    out.write(body)
                    out.write(bytes("\nendobj\n"))
                }
                val size = objects.keys.max() + 1
                val xref = out.filePointer
                out.write(bytes("xref\n0 $size\n0000000000 65535 f \n"))
                for (number in 1 until size) {
                    val offset = offsets[number]
                    out.write(bytes(if (offset == null) "0000000000 00000 f \n" else "%010d 00000 n \n".format(java.util.Locale.ROOT, offset)))
                }
                out.write(bytes("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"))
            }
            block(file)
        } finally { file.delete() }
    }
}
