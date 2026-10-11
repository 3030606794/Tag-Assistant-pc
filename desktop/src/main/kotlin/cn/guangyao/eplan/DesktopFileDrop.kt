@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package cn.guangyao.eplan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.util.IdentityHashMap

/** Receive files on Compose's real content root, rather than an AWT parent shadowed by its child. */
@Composable
internal fun DesktopFileDrop(
    canImport: () -> Boolean,
    unavailableReason: () -> String,
    onFiles: (List<File>) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentCanImport by rememberUpdatedState(canImport)
    val currentUnavailableReason by rememberUpdatedState(unavailableReason)
    val currentOnFiles by rememberUpdatedState(onFiles)
    var hovering by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val nativeActions = remember { IdentityHashMap<DropTarget, Int>() }

    // Compose owns the native target/listener. Keep that registration and only restrict its
    // negotiated action to Copy; an Explorer Move must never remove the user's original PDF.
    fun acceptDrag(event: DragAndDropEvent): Boolean {
        val native = event.nativeEvent as? DropTargetDragEvent ?: return false
        val accepted = currentCanImport() &&
            native.isDataFlavorSupported(DataFlavor.javaFileListFlavor) &&
            (native.sourceActions and DnDConstants.ACTION_COPY) != 0
        if (accepted) {
            val dropTarget = native.dropTargetContext.dropTarget
            if (!nativeActions.containsKey(dropTarget)) nativeActions[dropTarget] = dropTarget.defaultActions
            dropTarget.defaultActions = DnDConstants.ACTION_COPY
            native.acceptDrag(DnDConstants.ACTION_COPY)
        } else {
            native.rejectDrag()
        }
        return accepted
    }

    val target = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { updateHover(event) }
            override fun onEntered(event: DragAndDropEvent) { updateHover(event) }
            override fun onMoved(event: DragAndDropEvent) { updateHover(event) }
            override fun onChanged(event: DragAndDropEvent) { updateHover(event) }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }

            private fun updateHover(event: DragAndDropEvent) {
                hovering = runCatching { acceptDrag(event) }.getOrDefault(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                if (!currentCanImport()) {
                    message = currentUnavailableReason()
                    return false
                }
                return try {
                    val native = event.nativeEvent as? DropTargetDropEvent ?: return false
                    if ((native.sourceActions and DnDConstants.ACTION_COPY) == 0) return false
                    // The Compose desktop manager accepts before invoking onDrop. OpenJDK permits
                    // renegotiating an accepted drop; force Copy even if the user held Shift.
                    native.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = localPdfDropFiles(event.awtTransferable)
                    if (files.isEmpty()) {
                        message = "请拖入本机 PDF 文件，可一次拖入多份图纸。"
                        false
                    } else {
                        message = null
                        currentOnFiles(files)
                        true
                    }
                } catch (failure: Exception) {
                    message = "无法读取拖入的文件：${UserFacingErrors.describe(failure)}"
                    false
                }
            }
        }
    }
    DisposableEffect(nativeActions) {
        onDispose {
            nativeActions.forEach { (target, previous) -> runCatching { target.defaultActions = previous } }
            nativeActions.clear()
        }
    }
    LaunchedEffect(message) {
        if (message != null) { delay(5_000); message = null }
    }

    Box(modifier.fillMaxSize().dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            val accepted = runCatching { acceptDrag(event) }.getOrDefault(false)
            if (!accepted && !currentCanImport() &&
                runCatching { event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)) {
                message = currentUnavailableReason()
            }
            accepted
        },
        target = target,
    )) {
        content()
        if (hovering && currentCanImport()) {
            DesktopFileDropOverlay()
        } else message?.let { text ->
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 720.dp)) { Text(text) }
        }
    }
}

/** This same overlay can be rendered by CI without synthesizing a native Explorer drag. */
@Composable
internal fun DesktopFileDropOverlay(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxSize().padding(16.dp)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape)
        .border(2.dp, MaterialTheme.colorScheme.primary, shape), contentAlignment = Alignment.Center) {
        Surface(shape = shape, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol("description", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("松开鼠标，导入 PDF 图纸", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("支持一次拖入多份 · 保存应用副本，保留原始文件", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Never interpret web links, text or URI strings as local files. Import owns all file validation. */
internal fun localPdfDropFiles(transferable: Transferable): List<File> {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    val items = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return emptyList()
    if (items.any { it !is File }) return emptyList()
    val files = items.filterIsInstance<File>()
    // Pass mixed local batches to the existing importer so unsupported files receive its normal
    // per-file failure report; do not silently discard entries or replace original file names.
    return files.takeIf { batch -> batch.any { it.extension.equals("pdf", ignoreCase = true) } }.orEmpty()
}
