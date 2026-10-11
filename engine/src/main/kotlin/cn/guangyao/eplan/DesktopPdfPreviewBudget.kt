package cn.guangyao.eplan

import java.io.File
import java.io.InterruptedIOException

/**
 * A conservative gate before ordinary PDFBox loading. It reads only the bounded incremental
 * metadata graph, never prepares a page, decodes an image, or renders the incremental document.
 * Passing the gate is not a guarantee against malformed or unexpectedly expensive page content.
 */
object DesktopPdfPreviewBudget {
    const val MAX_FILE_BYTES: Long = 128L * 1024 * 1024
    const val MAX_PAGES: Int = 1500
    const val MAX_XREF_ENTRIES: Int = 200_000
    private const val MAX_STRUCTURAL_NODES: Int = 20_000
    private const val START_HEADROOM: Long = 192L * 1024 * 1024
    private const val STOP_HEADROOM: Long = 96L * 1024 * 1024

    data class Decision(val allowed: Boolean, val pageCount: Int = 0, val message: String? = null)

    fun inspect(source: File, cacheDirectory: File, isCancelled: () -> Boolean = { false }): Decision =
        inspectWithHeapBudget(source, cacheDirectory, isCancelled) {
            val runtime = Runtime.getRuntime()
            runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        }

    /** Injection affects only budget measurement; tests still parse real PDF metadata. */
    internal fun inspectWithHeapBudget(source: File, cacheDirectory: File, isCancelled: () -> Boolean,
        availableHeapBytes: () -> Long): Decision {
        fun reject(message: String, pages: Int = 0) = Decision(false, pages, message + " 已保存的提取结果保留，可使用外部 PDF 阅读器打开。")
        if (isCancelled()) throw InterruptedIOException("已取消预览检查。")
        if (!source.isFile || !source.canRead()) return Decision(false, message = "原 PDF 已不存在或无法读取，请重新导入。")
        // Check the real source before any parser or scratch file is constructed.
        if (source.length() > MAX_FILE_BYTES) return reject("此 PDF 超过 128 MiB，暂不启用内嵌预览。")
        if (availableHeapBytes() < START_HEADROOM) return reject("当前内存余量不足，暂不启用内嵌预览。")
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) return reject("无法创建预览检查缓存。")
        var memoryStopped = false
        fun shouldStop(): Boolean {
            if (isCancelled()) return true
            if (availableHeapBytes() < STOP_HEADROOM) { memoryStopped = true; return true }
            return false
        }
        return try {
            IncrementalPdfDocument(source, cacheDirectory, ::shouldStop, MAX_STRUCTURAL_NODES).use { session ->
                val pages = session.pdf.numberOfPages
                when {
                    pages <= 0 -> reject("此 PDF 没有可预览的页面。")
                    pages > MAX_PAGES -> reject("此 PDF 超过 1500 页，暂不启用内嵌预览。", pages)
                    session.pdf.document.xrefTable.size > MAX_XREF_ENTRIES -> reject("此 PDF 的对象目录较复杂，暂不启用内嵌预览。", pages)
                    availableHeapBytes() < START_HEADROOM -> reject("当前内存余量不足，暂不启用内嵌预览。", pages)
                    else -> Decision(true, pages)
                }
            }
        } catch (error: IncrementalPdfDocument.CancelledException) {
            if (memoryStopped) reject("检查期间内存余量不足，已停止内嵌预览。")
            else throw InterruptedIOException("已取消预览检查。").apply { initCause(error) }
        } catch (error: Exception) {
            if (isCancelled()) throw InterruptedIOException("已取消预览检查。").apply { initCause(error) }
            reject("无法在预览预算内读取此图纸：${UserFacingErrors.describe(error, "目录结构不可用。")}")
        }
    }
}
