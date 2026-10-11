package cn.guangyao.eplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.math.min

private data class DesktopRenderedPage(val image: ImageBitmap, val width: Float, val height: Float, val pageCount: Int)
private class DesktopPreviewUnavailable(val decision: DesktopPdfPreviewBudget.Decision) : IOException(decision.message ?: "此 PDF 暂时无法内嵌预览。")

/** Native page rendering is serialized; every request owns and closes a regular PDDocument. */
private object DesktopPageRenderer {
    private val mutex = Mutex()
    suspend fun render(path: String, page: Int): DesktopRenderedPage = mutex.withLock {
        withContext(Dispatchers.IO) {
            val requestContext = currentCoroutineContext()
            requestContext.ensureActive()
            val file = File(path)
            if (!file.isFile || !file.canRead()) throw IOException("原 PDF 已不存在或无法读取。")
            val scratchDirectory = Files.createTempDirectory("eplan-preview-").toFile()
            try {
                // The incremental reader inspects a bounded metadata graph only. Its deliberately
                // skipped image resources must never be copied into a document used for rendering.
                val decision = DesktopPdfPreviewBudget.inspect(file, scratchDirectory) { !requestContext.isActive }
                requestContext.ensureActive()
                if (!decision.allowed) throw DesktopPreviewUnavailable(decision)
                val memory = MemoryUsageSetting.setupTempFileOnly(256L * 1024 * 1024).setTempDir(scratchDirectory)
                PDDocument.load(file, memory).use { document ->
                    if (page !in 1..document.numberOfPages) throw IOException("页码超出范围，请输入 1–${document.numberOfPages}。")
                    val pdfPage = document.getPage(page - 1)
                    val crop = pdfPage.cropBox
                    val rotation = (pdfPage.rotation % 360 + 360) % 360
                    val width = if (rotation % 180 == 0) crop.width else crop.height
                    val height = if (rotation % 180 == 0) crop.height else crop.width
                    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) throw IOException("此页尺寸无效。")
                    // Bound raster allocation independently from the size of the original engineering sheet.
                    val scale = (1800f / maxOf(width, height)).coerceAtMost(2f)
                    val buffered = PDFRenderer(document).apply { isSubsamplingAllowed = true }.renderImage(page - 1, scale, ImageType.RGB)
                    try {
                        requestContext.ensureActive()
                        DesktopRenderedPage(buffered.toComposeImageBitmap(), width, height, document.numberOfPages)
                    } finally { buffered.flush() }
                }
            } finally {
                // PDFBox closes and removes its scratch files before this empty private directory.
                scratchDirectory.delete()
            }
        }
    }
}

@Composable
internal fun DesktopPdfPreview(document: ImportedDocument?, record: LabelRecord?, onOpenExternal: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var page by remember(document?.id) { mutableIntStateOf(1) }
    var pageText by remember(page) { mutableStateOf(page.toString()) }
    var zoom by remember(document?.id) { mutableFloatStateOf(1f) }
    var request by remember { mutableIntStateOf(0) }
    var rendered by remember(document?.id) { mutableStateOf<DesktopRenderedPage?>(null) }
    var inspectedPageCount by remember(document?.id) { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    LaunchedEffect(document?.id, record?.id) {
        page = record?.page?.takeIf { it > 0 } ?: 1
        zoom = 1f
    }
    LaunchedEffect(document?.id, page, request) {
        rendered = null; failure = null
        val source = document ?: return@LaunchedEffect
        loading = true
        try {
            val result = DesktopPageRenderer.render(source.path, page)
            inspectedPageCount = result.pageCount
            rendered = result
        }
        catch (error: CancellationException) { throw error }
        catch (error: DesktopPreviewUnavailable) { inspectedPageCount = error.decision.pageCount; failure = error.message }
        catch (_: OutOfMemoryError) { failure = "此页占用内存较多，暂时无法预览。可用外部 PDF 阅读器打开。" }
        catch (error: Exception) { failure = error.message ?: "无法预览此页。" }
        finally { loading = false }
    }
    LaunchedEffect(page, document?.id) { horizontal.scrollTo(0); vertical.scrollTo(0) }
    val count = rendered?.pageCount ?: inspectedPageCount.takeIf { it > 0 } ?: document?.pageCount ?: 0
    fun goToPage() {
        val number = pageText.toIntOrNull()
        if (number != null && number > 0 && (count == 0 || number <= count)) page = number
        else failure = if (count > 0) "请输入 1–$count 的页码。" else "请输入有效页码。"
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("图纸预览", style = MaterialTheme.typography.titleMedium)
                    Text(document?.name ?: "选中图纸或标签查看来源", style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onOpenExternal, enabled = document != null, modifier = Modifier.size(32.dp)) { Symbol("open_in_new", "使用外部阅读器打开") }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Symbol("close", "收起图纸预览") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { page-- }, enabled = document != null && page > 1, modifier = Modifier.size(32.dp)) { Symbol("arrow_back", "上一页") }
                OutlinedTextField(pageText, { if (it.length <= 6 && it.all(Char::isDigit)) pageText = it }, singleLine = true,
                    modifier = Modifier.width(64.dp).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { goToPage(); true } else false
                    }, textStyle = MaterialTheme.typography.bodySmall, enabled = document != null, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                Text("/ ${if (count > 0) count else "—"}", style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { page++ }, enabled = document != null && count > 0 && page < count, modifier = Modifier.size(32.dp)) { Symbol("chevron_right", "下一页") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = ::goToPage, enabled = document != null, modifier = Modifier.size(32.dp)) { Symbol("check", "跳转到输入页") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("缩放", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }, enabled = zoom > 1f, modifier = Modifier.size(28.dp)) { Symbol("zoom_out", "缩小") }
                TextButton(onClick = { zoom = 1f }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (zoom == 1f) "适合" else "${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall) }
                IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(5f) }, enabled = document != null && zoom < 5f, modifier = Modifier.size(28.dp)) { Symbol("zoom_in", "放大") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                val pageImage = rendered
                if (pageImage != null) {
                    val fit = min(maxWidth.value / pageImage.width, maxHeight.value / pageImage.height)
                    val imageWidth = (pageImage.width * fit * zoom).dp
                    val imageHeight = (pageImage.height * fit * zoom).dp
                    val primary = MaterialTheme.colorScheme.primary
                    val viewportWidth = maxWidth
                    val viewportHeight = maxHeight
                    Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(maxOf(viewportWidth, imageWidth)).height(maxOf(viewportHeight, imageHeight)), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(imageWidth).height(imageHeight)) {
                            Image(pageImage.image, "第 $page 页 PDF 原图", Modifier.fillMaxSize())
                            if (record != null && record.page == page && record.width > 0 && record.height > 0) Canvas(Modifier.fillMaxSize()) {
                                // Engine boxes already include CropBox translation and PDF /Rotate.
                                // Installation ordering never changes these source coordinates.
                                val sx = size.width / pageImage.width
                                val sy = size.height / pageImage.height
                                val topLeft = Offset(record.x * sx, record.y * sy)
                                val boxSize = Size(record.width * sx, record.height * sy)
                                drawRect(primary.copy(alpha = .20f), topLeft, boxSize)
                                drawRect(primary, topLeft, boxSize, style = Stroke(2.dp.toPx()))
                            }
                        }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
                if (loading) CircularProgressIndicator()
                if (document == null) Text("选择左侧图纸，或点标签的来源页。", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                failure?.let { message ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onOpenExternal, enabled = document != null, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
                            Symbol("open_in_new", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("用外部阅读器打开")
                        }
                        Button(onClick = { request++ }, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) { Text("重试预览") }
                    }
                }
            }
            if (record != null) Text(record.text, Modifier.fillMaxWidth().padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
