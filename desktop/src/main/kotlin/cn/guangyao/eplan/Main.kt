package cn.guangyao.eplan

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--cloud-preview") {
        renderCloudPreviews(File(args.getOrElse(1) { "previews" }))
        exitProcess(0)
    }
    if (args.firstOrNull() == "--smoke-test") {
        val output = File(args.getOrElse(1) { "smoke" })
        try {
            runPackagedSmoke(output, File(args.getOrElse(2) { error("Missing cloud fixture") }))
            exitProcess(0)
        } catch (failure: Throwable) {
            output.mkdirs()
            File(output, "smoke-error.txt").writeText(failure.stackTraceToString())
            exitProcess(1)
        }
    }
    val root = Path.of(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?: Path.of(System.getProperty("user.home"), ".local", "share").toString(), "EplanTagAssistantPC")
    val channel = try {
        Files.createDirectories(root)
        FileChannel.open(root.resolve("application.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    } catch (failure: Exception) {
        JOptionPane.showMessageDialog(null, "无法打开应用数据目录：${failure.message}", "EPLAN 标签助手", JOptionPane.ERROR_MESSAGE)
        return
    }
    val lock = try { channel.tryLock() } catch (failure: Exception) {
        channel.close()
        JOptionPane.showMessageDialog(null, "无法锁定应用数据目录：${failure.message}", "EPLAN 标签助手", JOptionPane.ERROR_MESSAGE)
        return
    }
    if (lock == null) {
        channel.close()
        JOptionPane.showMessageDialog(null, "EPLAN 标签助手已经打开，请切换到现有窗口。", "EPLAN 标签助手", JOptionPane.INFORMATION_MESSAGE)
        return
    }
    try {
        application {
            val controller = remember { DesktopController(root) }
            val state by controller.state.collectAsState()
            val scope = rememberCoroutineScope()
            var closeDialog by remember { mutableStateOf(false) }
            var closing by remember { mutableStateOf(false) }
            val systemDark = rememberWindowsDarkTheme()
            val dark = when (state.theme) {
                DesktopThemeMode.SYSTEM -> systemDark
                DesktopThemeMode.LIGHT -> false
                DesktopThemeMode.DARK -> true
            }
            val finish: () -> Unit = {
                if (!closing) {
                    closing = true
                    controller.cancel()
                    scope.launch {
                        controller.awaitIdle()
                        controller.close()
                        exitApplication()
                    }
                }
            }
            Window(
                onCloseRequest = { if (state.progress.running) closeDialog = true else finish() },
                title = "EPLAN 标签助手 · PC 初版 0.1.0",
                state = rememberWindowState(width = 1360.dp, height = 860.dp,
                    position = WindowPosition(Alignment.Center)),
                icon = painterResource("branding/app.png"),
            ) {
                DisposableEffect(window) {
                    window.minimumSize = Dimension(1040, 680)
                    val previous = window.dropTarget
                    window.dropTarget = object : DropTarget() {
                        override fun drop(event: DropTargetDropEvent) {
                            try {
                                val current = controller.state.value
                                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || current.progress.running || !current.loaded || closing) {
                                    event.rejectDrop(); return
                                }
                                event.acceptDrop(DnDConstants.ACTION_COPY)
                                val files = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                                    ?.filterIsInstance<File>().orEmpty()
                                controller.importFiles(files)
                                event.dropComplete(true)
                            } catch (_: Exception) { event.dropComplete(false) }
                        }
                    }
                    onDispose { window.dropTarget = previous }
                }
                DesktopTheme(dark) {
                    DesktopApp(controller)
                    if (closeDialog) AlertDialog(
                        onDismissRequest = { if (!closing) closeDialog = false },
                        title = { Text(if (closing) "正在保存并关闭" else "关闭工作台？") },
                        text = { Text("当前任务将取消，程序保存已经处理的内容后关闭。") },
                        confirmButton = { TextButton(onClick = finish, enabled = !closing) { Text("保存并关闭") } },
                        dismissButton = { TextButton(onClick = { closeDialog = false }, enabled = !closing) { Text("继续处理") } },
                    )
                }
            }
        }
    } finally {
        lock.release()
        channel.close()
    }
}

/** Read Windows app appearance off the UI thread and observe subsequent system changes. */
@Composable
private fun rememberWindowsDarkTheme(): Boolean {
    var dark by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            dark = withContext(Dispatchers.IO) {
                if (!System.getProperty("os.name").startsWith("Windows", true)) false
                else runCatching {
                    Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme") == 0
                }.getOrDefault(false)
            }
            delay(2500)
        }
    }
    return dark
}
