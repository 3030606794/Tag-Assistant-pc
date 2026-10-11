package cn.guangyao.eplan

import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Optional geometry evidence. Label text is always read by the native-text extractor. */
fun interface LayoutRasterSource {
    fun read(document: ImportedDocument, page: Int, checkCancelled: () -> Unit): AbInstallationOrder.Raster?
}

/**
 * Disposable ordinary PDFBox document, never the incremental extraction document. The engine
 * first tries its bounded embedded-artwork reader, and reaches this renderer only if needed.
 */
class DesktopRasterSource(private val cacheDirectory: File) : LayoutRasterSource {
    private class RenderLimit(message: String) : IOException(message)
    override fun read(document: ImportedDocument, page: Int, checkCancelled: () -> Unit): AbInstallationOrder.Raster? = synchronized(lock) {
        checkCancelled()
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        if (available < 96L * 1024 * 1024) return@synchronized null
        require(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "无法创建图纸渲染缓存目录。" }
        val source = File(document.path)
        if (!source.isFile || !source.canRead()) throw IOException("布局页已不可读取。")
        // The embedded reader still handles very large drawings. An ordinary fallback render
        // must not try to decode a multi-hundred-MiB unsupported image into the bounded heap.
        if (source.length() > 256L * 1024 * 1024) return@synchronized null
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly(1536L * 1024 * 1024).setTempDir(cacheDirectory)).use { pdf ->
            checkCancelled()
            if (page !in 1..pdf.numberOfPages) throw IOException("布局页已不存在。")
            val crop = pdf.getPage(page - 1).cropBox
            require(crop.width.isFinite() && crop.height.isFinite() && crop.width > 0f && crop.height > 0f) { "布局页尺寸无效。" }
            val scale = LayoutOrderRaster.MAX_EDGE.toFloat() / max(crop.width, crop.height)
            val renderer = object : PDFRenderer(pdf) {
                override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer = object : PageDrawer(parameters) {
                    private var operators = 0
                    override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
                        if (++operators % 256 == 0) checkCancelled()
                        if (operators > 2_000_000) throw RenderLimit("布局绘图指令超过安全处理范围。")
                        super.processOperator(operator, operands)
                    }
                    override fun operatorException(operator: Operator, operands: MutableList<COSBase>?, error: IOException) {
                        if (error is RenderLimit) throw error
                        super.operatorException(operator, operands, error)
                    }
                    override fun increaseLevel() {
                        if (level >= 48) throw RenderLimit("布局图形嵌套过深。")
                        super.increaseLevel()
                    }
                    override fun saveGraphicsState() {
                        if (graphicsStackSize >= 256) throw RenderLimit("布局图形状态嵌套过深。")
                        super.saveGraphicsState()
                    }
                }
            }.apply { isSubsamplingAllowed = true }
            val image = renderer.renderImage(page - 1, scale, ImageType.RGB)
            try {
                checkCancelled()
                AbInstallationOrder.Raster(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
            } finally { image.flush() }
        }
    }

    companion object { private val lock = Any() }
}

/** Shared bounded sampling dimensions for the embedded and ordinary desktop renderers. */
internal object LayoutOrderRaster {
    const val MAX_EDGE = 1200
    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = MAX_EDGE.toDouble() / max(width, height)
        return (width * scale).roundToInt().coerceIn(1, MAX_EDGE) to
            (height * scale).roundToInt().coerceIn(1, MAX_EDGE)
    }
}
