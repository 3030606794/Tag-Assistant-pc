package cn.guangyao.eplan

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.DeflaterOutputStream

/** Runs only in cloud CI: exercise the production image reader with independently placed pixels. */
class EmbeddedLayoutRasterStreamTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root


    private val rgb = byteArrayOf(
        -1, 0, 0, 0, -1, 0, // Source top row: red, green.
        0, 0, -1, -1, -1, 0, // Source bottom row: blue, yellow.
    )
    private val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt())

    @Test fun rawImageReadsOnlyItsCompressedRangeAndCanReloadAfterPageRelease() = withFixture { file, compressed ->
        IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
            val retained = session.retainedObjectCount()
            repeat(3) {
                val image = image(session)
                session.openImageStream(image).use { raw ->
                    requireNotNull(raw)
                    assertEquals(compressed.size.toLong() - 1, raw.skip((compressed.size - 1).toLong()))
                    assertEquals(compressed.last().toInt() and 255, raw.read())
                    assertEquals(-1, raw.read())
                    assertEquals(0L, raw.skip(Long.MAX_VALUE))
                    assertEquals(0, raw.read(ByteArray(1), 0, 0))
                    assertThrows(IndexOutOfBoundsException::class.java) { raw.read(ByteArray(2), 1, 2) }
                }
                session.openImageStream(image).use { raw ->
                    assertArrayEquals("Neither the next PDF object nor endstream may leak into the image", compressed, requireNotNull(raw).readBytes())
                }
                session.releasePage()
                assertNull("Stale image handles must not survive page release", session.openImageStream(image))
                assertEquals("Image metadata must not accumulate", retained, session.retainedObjectCount())
            }
        }
    }

    @Test fun cancellationInterruptsAlreadyOpenImageStreamsAndAllowsAChangedPageAttempt() = withFixture { file, _ ->
        var cancelled = false
        IncrementalPdfDocument(file, cacheDirectory) { cancelled }.use { session ->
            val image = image(session)
            val raw = requireNotNull(session.openImageStream(image))
            try {
                cancelled = true
                assertThrows(IncrementalPdfDocument.CancelledException::class.java) { raw.read() }
                assertThrows(IncrementalPdfDocument.CancelledException::class.java) { raw.read(ByteArray(2)) }
                assertThrows(IncrementalPdfDocument.CancelledException::class.java) { raw.skip(1) }
                assertThrows(IncrementalPdfDocument.CancelledException::class.java) { session.openImageStream(image) }
            } finally { raw.close(); cancelled = false }
            session.releasePage()
            assertNotNull(EmbeddedLayoutRaster.read(session, 1) {})
        }
    }

    @Test fun aLargeUnneededImageStaysOnDiskWhileTheSmallLayoutRemainsReadable() = withFixture(largeImage = true) { file, _ ->
        assertTrue(file.length() > 65L * 1024 * 1024)
        IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
            val retained = session.retainedObjectCount()
            val page = session.preparePage(1).getPage(0)
            val huge = page.resources.cosObject.getCOSDictionary(COSName.XOBJECT)
                .getDictionaryObject(COSName.getPDFName("Huge")) as COSStream
            assertNull("Images exceeding the raw stream budget must remain unavailable", session.openImageStream(huge))
            session.releasePage()
            assertEquals(retained, session.retainedObjectCount())
            val raster = requireNotNull(EmbeddedLayoutRaster.read(session, 1) {})
            assertEquals(colors[0], pixel(raster, 40f, 50f, 120f, 160f))
            assertEquals(retained, session.retainedObjectCount())
        }
    }

    @Test fun nonzeroCropAndAllPageRotationsKeepTheFourSourceQuadrantsAndWhiteMargins() {
        for (rotation in listOf(0, 90, 180, 270)) withFixture(rotation = rotation) { file, _ ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val raster = requireNotNull(EmbeddedLayoutRaster.read(session, 1) {})
                val width = if (rotation % 180 == 0) 120f else 160f
                val height = if (rotation % 180 == 0) 160f else 120f
                assertEquals(if (rotation % 180 == 0) 900 else 1200, raster.width)
                assertEquals(if (rotation % 180 == 0) 1200 else 900, raster.height)
                listOf(40f to 50f, 80f to 50f, 40f to 110f, 80f to 110f).forEachIndexed { i, point ->
                    // Explicit expected rotations; do not use the production transform helper.
                    val turned = when (rotation) {
                        90 -> 160f - point.second to point.first
                        180 -> 120f - point.first to 160f - point.second
                        270 -> point.second to 120f - point.first
                        else -> point
                    }
                    assertEquals("Page rotation $rotation, source quadrant $i", colors[i], pixel(raster, turned.first, turned.second, width, height))
                }
                assertEquals(-1, pixel(raster, 5f, 5f, width, height))
            }
        }
    }

    @Test fun invertedAndQuarterTurnImageMatricesMatchTheActualEplanPlacementConventions() {
        val cases = listOf(
            "80 0 0 -120 30 160" to listOf(40f to 110f, 80f to 110f, 40f to 50f, 80f to 50f),
            "0 120 80 0 30 40" to listOf(80f to 110f, 80f to 50f, 40f to 110f, 40f to 50f),
        )
        for ((matrix, points) in cases) withFixture(matrix = matrix) { file, _ ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val raster = requireNotNull(EmbeddedLayoutRaster.read(session, 1) {})
                points.forEachIndexed { index, point ->
                    assertEquals("Image matrix $matrix, quadrant $index", colors[index], pixel(raster, point.first, point.second, 120f, 160f))
                }
            }
        }
    }

    @Test fun imageInsideAFormUsesTheCombinedOuterAndInnerTransform() = withFixture(inForm = true) { file, _ ->
        IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
            val raster = requireNotNull(EmbeddedLayoutRaster.read(session, 1) {})
            listOf(40f to 50f, 80f to 50f, 40f to 110f, 80f to 110f).forEachIndexed { i, point ->
                assertEquals(colors[i], pixel(raster, point.first, point.second, 120f, 160f))
            }
        }
    }

    @Test fun truncatedOrExcessDecodedPixelsCannotBecomeSuccessfulGeometry() {
        for (body in listOf(rgb.copyOf(11), rgb.copyOf(13))) withFixture(imageBytes = body) { file, _ ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                val retained = session.retainedObjectCount()
                assertThrows(IOException::class.java) { EmbeddedLayoutRaster.read(session, 1) {} }
                assertEquals(retained, session.retainedObjectCount())
            }
        }
    }

    @Test fun unsupportedImageMetadataAndOversizedDecodedPixelsReturnNoGeometry() {
        for (options in listOf("/Decode [1 0 1 0 1 0]", "/DecodeParms << /Predictor 12 >>", "/ImageMask true")) {
            withFixture(imageOptions = options) { file, _ ->
                IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                    assertNull(options, EmbeddedLayoutRaster.read(session, 1) {})
                }
            }
        }
        withFixture(imageWidth = 32768, imageHeight = 32768) { file, _ ->
            IncrementalPdfDocument(file, cacheDirectory) { false }.use { session ->
                assertNull("A decompression bomb must be rejected before reading pixels", EmbeddedLayoutRaster.read(session, 1) {})
            }
        }
    }

    private fun image(session: IncrementalPdfDocument): COSStream = session.preparePage(1).getPage(0)
        .resources.cosObject.getCOSDictionary(COSName.XOBJECT).getDictionaryObject(COSName.getPDFName("Im0")) as COSStream

    private fun pixel(raster: AbInstallationOrder.Raster, x: Float, y: Float, width: Float, height: Float): Int =
        raster.pixels[(y / height * raster.height).toInt() * raster.width + (x / width * raster.width).toInt()]

    private fun withFixture(
        rotation: Int = 0,
        matrix: String = "80 0 0 120 30 40",
        inForm: Boolean = false,
        largeImage: Boolean = false,
        imageOptions: String = "",
        imageWidth: Int = 2,
        imageHeight: Int = 2,
        imageBytes: ByteArray = rgb,
        block: (File, ByteArray) -> Unit,
    ) {

        val compressed = ByteArrayOutputStream().also { buffer -> DeflaterOutputStream(buffer).use { it.write(imageBytes) } }.toByteArray()
        val file = File(cacheDirectory, "embedded-stream-${UUID.randomUUID()}.pdf")
        try {
            RandomAccessFile(file, "rw").use { out ->
                val offsets = linkedMapOf<Int, Long>()
                fun text(value: String) = out.write(value.toByteArray(StandardCharsets.ISO_8859_1))
                fun obj(id: Int, value: String) { offsets[id] = out.filePointer; text("$id 0 obj\n$value\nendobj\n") }
                fun stream(id: Int, dictionary: String, data: ByteArray) {
                    offsets[id] = out.filePointer
                    text("$id 0 obj\n<< /Length ${data.size} $dictionary >>\nstream\n")
                    out.write(data)
                    text("\nendstream\nendobj\n")
                }
                text("%PDF-1.7\n")
                obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
                obj(2, "<< /Type /Pages /Count 1 /Kids [3 0 R] >>")
                val resources = "/Im0 5 0 R ${if (inForm) "/Fm0 6 0 R" else ""} ${if (largeImage) "/Huge 7 0 R" else ""}"
                obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 240] /CropBox [10 20 130 180] /Rotate $rotation /Resources << /XObject << $resources >> >> /Contents 4 0 R >>")
                val content = if (inForm) "q 1 0 0 1 30 40 cm /Fm0 Do Q" else "q $matrix cm /Im0 Do Q"
                stream(4, "", content.toByteArray(StandardCharsets.ISO_8859_1))
                stream(5, "/Type /XObject /Subtype /Image /Width $imageWidth /Height $imageHeight /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode $imageOptions", compressed)
                if (inForm) stream(6, "/Type /XObject /Subtype /Form /BBox [0 0 80 120] /Resources << /XObject << /Im0 5 0 R >> >>",
                    "q 80 0 0 120 0 0 cm /Im0 Do Q".toByteArray(StandardCharsets.ISO_8859_1))
                if (largeImage) {
                    val length = 65L * 1024 * 1024
                    offsets[7] = out.filePointer
                    text("7 0 obj\n<< /Type /XObject /Subtype /Image /Width 1024 /Height 66560 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length $length >>\nstream\n")
                    out.seek(out.filePointer + length)
                    text("\nendstream\nendobj\n")
                }
                val xref = out.filePointer
                val count = offsets.keys.max() + 1
                text("xref\n0 $count\n0000000000 65535 f \n")
                for (id in 1 until count) text(offsets[id]?.let { "%010d 00000 n \n".format(java.util.Locale.ROOT, it) } ?: "0000000000 00000 f \n")
                text("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            }
            block(file, compressed)
        } finally { file.delete() }
    }
}
