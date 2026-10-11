package cn.guangyao.eplan

import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.DrawObject
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.state.Concatenate
import org.apache.pdfbox.contentstream.operator.state.Restore
import org.apache.pdfbox.contentstream.operator.state.Save
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDPage
import java.io.IOException
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Read the cabinet artwork already embedded in an EPLAN page. Native text still comes from
 * PositionedStripper. No whole-document native renderer, bitmap, or full-size decoded image
 * is needed: one RGB row is inflated at a time into a bounded page-coordinate sampling grid.
 */
internal object EmbeddedLayoutRaster {
    private data class Point(val x: Float, val y: Float)
    private data class Image(val stream: COSStream, val width: Int, val height: Int,
        val origin: Point, val across: Point, val down: Point) {
        val determinant get() = across.x * down.y - across.y * down.x
    }
    private class Limit(message: String) : IOException(message)

    fun read(session: IncrementalPdfDocument, number: Int, checkCancelled: () -> Unit): AbInstallationOrder.Raster? {
        try {
            checkCancelled()
            val page = session.preparePage(number).getPage(number - 1)
            val collector = Collector(page, checkCancelled)
            collector.processPage(page)
            val rotation = (page.rotation % 360 + 360) % 360
            val pageWidth = if (rotation % 180 == 0) page.cropBox.width else page.cropBox.height
            val pageHeight = if (rotation % 180 == 0) page.cropBox.height else page.cropBox.width
            val images = collector.images.sortedByDescending { abs(it.determinant) }
            val main = images.firstOrNull() ?: return null
            // A title logo or a small product illustration is not a cabinet background.
            if (abs(main.determinant) < pageWidth * pageHeight * .2f) return null
            if (images.getOrNull(1)?.let { abs(it.determinant) > abs(main.determinant) * .25f } == true) return null
            return sample(session, main, pageWidth, pageHeight, checkCancelled)
        } finally {
            session.releasePage()
        }
    }

    private class Collector(private val page: PDPage, private val checkCancelled: () -> Unit) : PDFStreamEngine() {
        val images = mutableListOf<Image>()
        private var operators = 0
        init { addOperator(Concatenate()); addOperator(Save()); addOperator(Restore()); addOperator(DrawObject()) }

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
            if (++operators % 256 == 0) checkCancelled()
            if (operators > 2_000_000) throw Limit("布局绘图指令超过安全范围")
            if (operator.name == "Do") {
                checkCancelled()
                val name = operands?.firstOrNull() as? COSName
                val stream = name?.let { resources?.cosObject?.getCOSDictionary(COSName.XOBJECT)?.getDictionaryObject(it) } as? COSStream
                if (stream != null && stream.getCOSName(COSName.SUBTYPE) == COSName.IMAGE) {
                    if (images.size >= 128) throw Limit("布局图片数量超过安全范围")
                    if (supported(stream)) {
                        val matrix = graphicsState.currentTransformationMatrix
                        fun point(x: Float, y: Float): Point {
                            val p = matrix.transformPoint(x, y)
                            val crop = page.cropBox
                            val localX = p.x - crop.lowerLeftX
                            val localY = crop.upperRightY - p.y
                            val turned = EplanParsing.rotate(localX, localY, crop.width, crop.height,
                                (page.rotation % 360 + 360) % 360)
                            return Point(turned.first, turned.second)
                        }
                        val origin = point(0f, 1f)
                        val right = point(1f, 1f); val bottom = point(0f, 0f)
                        val image = Image(stream, stream.getInt(COSName.WIDTH), stream.getInt(COSName.HEIGHT),
                            origin, Point(right.x - origin.x, right.y - origin.y), Point(bottom.x - origin.x, bottom.y - origin.y))
                        if (image.determinant.isFinite() && abs(image.determinant) > 1f) images += image
                    }
                    return
                }
            }
            super.processOperator(operator, operands)
        }

        override fun operatorException(operator: Operator, operands: MutableList<COSBase>?, error: IOException) {
            if (error is Limit || error is IncrementalPdfDocument.CancelledException) throw error
            super.operatorException(operator, operands, error)
        }
        override fun increaseLevel() {
            if (level >= 48) throw Limit("布局图形嵌套过深")
            super.increaseLevel()
        }
        override fun saveGraphicsState() {
            if (graphicsStackSize >= 256) throw Limit("布局图形状态嵌套过深")
            super.saveGraphicsState()
        }
    }

    private fun supported(stream: COSStream): Boolean {
        val width = stream.getInt(COSName.WIDTH); val height = stream.getInt(COSName.HEIGHT)
        return width in 1..32768 && height in 1..32768 && width.toLong() * height <= 24_000_000L &&
            stream.getInt(COSName.BITS_PER_COMPONENT) == 8 && stream.getCOSName(COSName.COLORSPACE) == COSName.DEVICERGB &&
            stream.getDictionaryObject(COSName.FILTER) == COSName.FLATE_DECODE &&
            listOf(COSName.DECODE_PARMS, COSName.DECODE, COSName.MASK, COSName.SMASK).all { stream.getDictionaryObject(it) == null } &&
            !stream.getBoolean(COSName.IMAGE_MASK, false)
    }

    private fun sample(session: IncrementalPdfDocument, image: Image, pageWidth: Float, pageHeight: Float,
        checkCancelled: () -> Unit): AbInstallationOrder.Raster? {
        val compressed = session.openImageStream(image.stream) ?: return null
        compressed.use { raw ->
            val (width, height) = LayoutOrderRaster.dimensions(ceil(pageWidth.toDouble()).toInt(), ceil(pageHeight.toDouble()).toInt())
            val pixels = IntArray(width * height) { -1 }
            // Intrusive row buckets use one integer per destination pixel, never boxed points or
            // source-sized RGB arrays. Each destination pixel is visited at most twice.
            val next = IntArray(pixels.size) { -1 }
            val heads = IntArray(image.height) { -1 }
            val stepX = pageWidth / width; val stepY = pageHeight / height
            val ux = stepX * image.down.y / image.determinant
            val uy = -stepY * image.down.x / image.determinant
            val u0 = ((stepX * .5f - image.origin.x) * image.down.y -
                (stepY * .5f - image.origin.y) * image.down.x) / image.determinant
            val vx = -stepX * image.across.y / image.determinant
            val vy = stepY * image.across.x / image.determinant
            val v0 = (image.across.x * (stepY * .5f - image.origin.y) -
                image.across.y * (stepX * .5f - image.origin.x)) / image.determinant
            val corners = listOf(image.origin, Point(image.origin.x + image.across.x, image.origin.y + image.across.y),
                Point(image.origin.x + image.down.x, image.origin.y + image.down.y),
                Point(image.origin.x + image.across.x + image.down.x, image.origin.y + image.across.y + image.down.y))
            val left = max(0, floor(corners.minOf { it.x } / pageWidth * width).toInt())
            val right = min(width - 1, ceil(corners.maxOf { it.x } / pageWidth * width).toInt())
            val top = max(0, floor(corners.minOf { it.y } / pageHeight * height).toInt())
            val bottom = min(height - 1, ceil(corners.maxOf { it.y } / pageHeight * height).toInt())
            for (y in top..bottom) {
                checkCancelled()
                for (x in left..right) {
                    val u = ux * x + uy * y + u0
                    val v = vx * x + vy * y + v0
                    if (u < 0f || u >= 1f || v < 0f || v >= 1f) continue
                    val sourceY = min(image.height - 1, (v * image.height).toInt())
                    val index = y * width + x
                    next[index] = heads[sourceY]; heads[sourceY] = index
                }
            }
            InflaterInputStream(raw).use { decoded ->
                val row = ByteArray(image.width * 3)
                for (y in 0 until image.height) {
                    checkCancelled()
                    var offset = 0
                    while (offset < row.size) {
                        val count = decoded.read(row, offset, row.size - offset)
                        if (count <= 0) throw IOException("布局底图数据不完整")
                        offset += count
                    }
                    var index = heads[y]
                    while (index >= 0) {
                        val u = ux * (index % width) + uy * (index / width) + u0
                        val sourceX = (u * image.width).toInt().coerceIn(0, image.width - 1) * 3
                        pixels[index] = -0x1000000 or ((row[sourceX].toInt() and 255) shl 16) or
                            ((row[sourceX + 1].toInt() and 255) shl 8) or (row[sourceX + 2].toInt() and 255)
                        index = next[index]
                    }
                }
                if (decoded.read() != -1) throw IOException("布局底图尺寸与数据不一致")
            }
            return AbInstallationOrder.Raster(width, height, pixels)
        }
    }
}
