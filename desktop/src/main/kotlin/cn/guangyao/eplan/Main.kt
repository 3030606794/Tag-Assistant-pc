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
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    initializeChineseDesktop()
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
        JOptionPane.showMessageDialog(null, "无法打开应用数据目录：${UserFacingErrors.describe(failure)}", "EPLAN 标签助手", JOptionPane.ERROR_MESSAGE)
        return
    }
    val lock = try { channel.tryLock() } catch (failure: Exception) {
        channel.close()
        JOptionPane.showMessageDialog(null, "无法锁定应用数据目录：${UserFacingErrors.describe(failure)}", "EPLAN 标签助手", JOptionPane.ERROR_MESSAGE)
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
                    val waitingForTask = controller.state.value.progress.running
                    closing = true
                    controller.cancel()
                    scope.launch {
                        controller.awaitIdle()
                        if (waitingForTask && controller.state.value.lastTaskFailed) {
                            closing = false
                            closeDialog = false
                        } else {
                            controller.close()
                            exitApplication()
                        }
                    }
                }
            }
            Window(
                onCloseRequest = { if (state.progress.running) closeDialog = true else finish() },
                title = "EPLAN 标签助手 · 电脑版 0.1.2",
                state = rememberWindowState(width = 1440.dp, height = 900.dp,
                    position = WindowPosition(Alignment.Center)),
                icon = painterResource("branding/app.png"),
            ) {
                DisposableEffect(window) {
                    window.minimumSize = Dimension(1040, 680)
                    onDispose { }
                }
                DesktopTheme(dark) {
                    DesktopFileDrop(
                        canImport = {
                            val current = controller.state.value
                            current.loaded && !current.progress.running && !closing && !closeDialog
                        },
                        unavailableReason = {
                            val current = controller.state.value
                            when {
                                closing || closeDialog -> "正在关闭或确认关闭，请稍后再拖入图纸。"
                                !current.loaded -> "正在读取本机工作区，请稍后再拖入图纸。"
                                else -> "当前任务尚未完成，请等待或取消后再拖入图纸。"
                            }
                        },
                        onFiles = controller::importFiles,
                    ) { DesktopApp(controller) }
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
