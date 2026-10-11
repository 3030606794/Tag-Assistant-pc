package cn.guangyao.eplan

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Windows Common Item Dialog; there is deliberately no legacy Swing fallback on Windows. */
internal object DesktopFileDialogs {
    private val showing = AtomicBoolean(false)
    val isWindows: Boolean get() = System.getProperty("os.name").startsWith("Windows", true)

    fun choosePdfFiles(onSelected: (List<File>) -> Unit, onFailure: (String) -> Unit) =
        request(false, null, onSelected, onFailure)

    fun chooseExportDirectory(initialDirectory: File?, onSelected: (File) -> Unit, onFailure: (String) -> Unit) =
        request(true, initialDirectory, { files -> files.firstOrNull()?.let(onSelected) }, onFailure)

    private fun request(directory: Boolean, initialDirectory: File?, onSelected: (List<File>) -> Unit, onFailure: (String) -> Unit) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { request(directory, initialDirectory, onSelected, onFailure) }
            return
        }
        if (!showing.compareAndSet(false, true)) {
            onFailure("文件选择器已经打开，请先完成当前选择。")
            return
        }
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: Window.getWindows().firstOrNull { it.isVisible && it.isDisplayable }
        if (!isWindows) {
            try { chooseWithSwing(directory, initialDirectory, owner)?.let(onSelected) }
            catch (error: Exception) { onFailure(UserFacingErrors.describe(error, "无法打开文件选择器，请重试。")) }
            finally { showing.set(false) }
            return
        }
        val ownerPointer = try { owner?.takeIf { it.isDisplayable }?.let(Native::getWindowPointer) }
        catch (error: Exception) {
            showing.set(false)
            onFailure(UserFacingErrors.describe(error, "无法打开 Windows 系统文件选择器，请重试。"))
            return
        }
        Thread({
            val selected = runCatching {
                WindowsFilePicker.withApartment {
                    WindowsFilePicker.create(directory, initialDirectory).use { dialog -> dialog.show(ownerPointer) }
                }
            }
            SwingUtilities.invokeLater {
                showing.set(false)
                selected.fold(
                    onSuccess = { files -> files?.let(onSelected) },
                    onFailure = { error -> onFailure(UserFacingErrors.describe(error, "无法打开 Windows 系统文件选择器，请重试。")) },
                )
            }
        }, "eplan-windows-file-picker").apply { isDaemon = true; start() }
    }

    private fun chooseWithSwing(directory: Boolean, initialDirectory: File?, owner: Window?): List<File>? {
        val chooser = JFileChooser(initialDirectory?.takeIf(File::isDirectory)).apply {
            dialogTitle = if (directory) "选择 Excel 导出目录" else "导入电气图纸 PDF"
            approveButtonText = if (directory) "选择文件夹" else "导入"
            fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = !directory
            if (!directory) { fileFilter = FileNameExtensionFilter("PDF 图纸 (*.pdf)", "pdf"); isAcceptAllFileFilterUsed = false }
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        return if (directory) listOf(chooser.selectedFile)
        else chooser.selectedFiles.toList().ifEmpty { listOf(chooser.selectedFile) }
    }

    /** Cloud tests exercise real COM configuration and Unicode paths without showing a window. */
    fun verifyWindowsBindings(directory: Boolean, sample: File): WindowsPickerVerification {
        check(isWindows) { "此检查只支持 Windows。" }
        val task = FutureTask {
            WindowsFilePicker.withApartment {
                WindowsFilePicker.create(directory, sample.takeIf(File::isDirectory) ?: sample.parentFile).use { dialog ->
                    val incompatibleApartment = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED).toInt()
                    // A successful extra initialization must also be balanced, even if this probe fails.
                    if (incompatibleApartment >= 0) Ole32.INSTANCE.CoUninitialize()
                    val name = if (directory) "导出文件夹 中文路径" else "电气图纸 中文路径.pdf"
                    dialog.setFileName(name)
                    WindowsPickerVerification(
                        options = dialog.options(), unicodeFileName = dialog.fileName(),
                        initialDirectory = dialog.folderPath(),
                        shellItemPath = WindowsFilePicker.shellItem(sample).use { it.fileSystemPath() },
                        staApartment = incompatibleApartment == RPC_E_CHANGED_MODE,
                        pdfFilter = dialog.pdfFilter, fileTypeIndex = if (directory) null else dialog.fileTypeIndex(),
                        title = dialog.title, confirmation = dialog.confirmation,
                    )
                }
            }
        }
        Thread(task, "eplan-file-picker-check").apply { isDaemon = true; start() }
        return try { task.get(30, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
    }

    fun isCancellation(result: Int): Boolean = result == HRESULT_CANCELLED
    private const val RPC_E_CHANGED_MODE = -2147417850 // 0x80010106
    private const val HRESULT_CANCELLED = -2147023673 // HRESULT_FROM_WIN32(ERROR_CANCELLED)
}

internal data class WindowsPickerVerification(
    val options: Int,
    val unicodeFileName: String,
    val initialDirectory: String,
    val shellItemPath: String,
    val staApartment: Boolean,
    val pdfFilter: String?,
    val fileTypeIndex: Int?,
    val title: String,
    val confirmation: String,
)

/** COM vtable slots follow shobjidl_core.h (IFileDialog, IFileOpenDialog, IShellItem/Array). */
private object WindowsFilePicker {
    const val FOS_PICKFOLDERS = 0x20
    const val FOS_FORCEFILESYSTEM = 0x40
    const val FOS_ALLOWMULTISELECT = 0x200
    const val FOS_PATHMUSTEXIST = 0x800
    const val FOS_FILEMUSTEXIST = 0x1000
    const val FOS_NOCHANGEDIR = 0x8
    private const val CLSCTX_INPROC_SERVER = 0x1
    private val classId = Guid.GUID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}")
    private val dialogId = Guid.GUID("{D57C7288-D4AD-4768-BE02-9D969532D960}")
    private val shellItemIid = Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}")
    private val shellItemId = Guid.REFIID(shellItemIid)

    fun <T> withApartment(block: () -> T): T {
        checkResult(Ole32.INSTANCE.CoInitializeEx(Pointer.NULL,
            Ole32.COINIT_APARTMENTTHREADED or Ole32.COINIT_DISABLE_OLE1DDE).toInt())
        try { return block() }
        finally { Ole32.INSTANCE.CoUninitialize() }
    }

    fun create(directory: Boolean, initialDirectory: File?): NativeOpenDialog {
        val pointer = PointerByReference()
        checkResult(Ole32.INSTANCE.CoCreateInstance(classId, Pointer.NULL, CLSCTX_INPROC_SERVER, dialogId, pointer).toInt())
        val dialog = NativeOpenDialog(requirePointer(pointer), directory)
        try { dialog.configure(initialDirectory); return dialog }
        catch (error: Throwable) { dialog.close(); throw error }
    }

    fun shellItem(file: File): NativeShellItem {
        val pointer = PointerByReference()
        checkResult(WindowsShellItems.INSTANCE.SHCreateItemFromParsingName(WString(file.absolutePath), Pointer.NULL, shellItemId, pointer).toInt())
        return NativeShellItem(requirePointer(pointer))
    }

    fun requirePointer(reference: PointerByReference): Pointer = reference.value
        ?: throw IOException("Windows 文件选择器没有返回有效文件。")

    fun checkResult(result: Int) {
        if (result < 0) throw IOException("Windows 系统文件选择器操作未完成（错误码 ${Integer.toHexString(result)}），请重试。")
    }

    fun allocatedText(call: (PointerByReference) -> Int): String {
        val result = PointerByReference()
        try { checkResult(call(result)); return requirePointer(result).getWideString(0) }
        finally { result.value?.let(Ole32.INSTANCE::CoTaskMemFree) }
    }
}

internal interface WindowsShellItems : StdCallLibrary {
    fun SHCreateItemFromParsingName(path: WString, binding: Pointer?, interfaceId: Guid.REFIID, result: PointerByReference): HRESULT
    companion object {
        val INSTANCE: WindowsShellItems by lazy { Native.load("shell32", WindowsShellItems::class.java, W32APIOptions.UNICODE_OPTIONS) }
    }
}

// Public JVM fields let JNA lay out the native two-pointer COMDLG_FILTERSPEC structure.
@Structure.FieldOrder("name", "pattern")
internal class WindowsPdfFilterSpec : Structure() {
    @JvmField var name: WString = WString("PDF 图纸 (*.pdf)")
    @JvmField var pattern: WString = WString("*.pdf")
}

private open class NativeCom(pointer: Pointer) : Unknown(pointer), AutoCloseable {
    private var released = false
    fun call(slot: Int, vararg arguments: Any?): Int = _invokeNativeInt(slot, arrayOf(pointer, *arguments))
    override fun close() { if (!released) { released = true; Release() } }
}

private class NativeOpenDialog(pointer: Pointer, private val directory: Boolean) : NativeCom(pointer) {
    val title = if (directory) "选择 Excel 导出目录" else "导入电气图纸 PDF"
    val confirmation = if (directory) "选择文件夹" else "导入"
    private var filter: WindowsPdfFilterSpec? = null
    val pdfFilter: String? get() = filter?.pattern?.toString()

    fun configure(initialDirectory: File?) {
        val common = options() or WindowsFilePicker.FOS_FORCEFILESYSTEM or WindowsFilePicker.FOS_PATHMUSTEXIST or WindowsFilePicker.FOS_NOCHANGEDIR
        val flags = if (directory) (common or WindowsFilePicker.FOS_PICKFOLDERS) and
            WindowsFilePicker.FOS_ALLOWMULTISELECT.inv() and WindowsFilePicker.FOS_FILEMUSTEXIST.inv()
        else (common or WindowsFilePicker.FOS_ALLOWMULTISELECT or WindowsFilePicker.FOS_FILEMUSTEXIST) and
            WindowsFilePicker.FOS_PICKFOLDERS.inv()
        WindowsFilePicker.checkResult(call(9, flags))
        WindowsFilePicker.checkResult(call(17, WString(title)))
        WindowsFilePicker.checkResult(call(18, WString(confirmation)))
        WindowsFilePicker.checkResult(call(19, WString(if (directory) "文件夹：" else "文件名：")))
        WindowsFilePicker.checkResult(call(24, Guid.GUID(if (directory)
            "{CC92D427-5FF4-4A80-AB6D-3DA995310AFA}" else "{2F5E371F-2B1C-4975-AE55-ED4FBA97E86A}")))
        if (!directory) {
            filter = WindowsPdfFilterSpec().apply { write() }
            WindowsFilePicker.checkResult(call(4, 1, filter!!.pointer))
            WindowsFilePicker.checkResult(call(5, 1))
            WindowsFilePicker.checkResult(call(22, WString("pdf")))
        }
        initialDirectory?.takeIf(File::isDirectory)?.let { folder ->
            WindowsFilePicker.shellItem(folder).use { WindowsFilePicker.checkResult(call(12, it.pointer)) }
        }
    }

    fun options(): Int = IntByReference().let { WindowsFilePicker.checkResult(call(10, it)); it.value }
    fun fileTypeIndex(): Int = IntByReference().let { WindowsFilePicker.checkResult(call(6, it)); it.value }
    fun setFileName(name: String) = WindowsFilePicker.checkResult(call(15, WString(name)))
    fun fileName(): String = WindowsFilePicker.allocatedText { call(16, it) }
    fun folderPath(): String {
        val result = PointerByReference()
        WindowsFilePicker.checkResult(call(13, result))
        return NativeShellItem(WindowsFilePicker.requirePointer(result)).use { it.fileSystemPath() }
    }

    fun show(owner: Pointer?): List<File>? {
        val shown = call(3, owner)
        if (DesktopFileDialogs.isCancellation(shown)) return null
        WindowsFilePicker.checkResult(shown)
        val result = PointerByReference()
        if (directory) {
            WindowsFilePicker.checkResult(call(20, result))
            return NativeShellItem(WindowsFilePicker.requirePointer(result)).use { listOf(File(it.fileSystemPath())) }
        }
        WindowsFilePicker.checkResult(call(27, result))
        return NativeCom(WindowsFilePicker.requirePointer(result)).use { array ->
            val count = IntByReference()
            WindowsFilePicker.checkResult(array.call(7, count))
            if (count.value <= 0) throw IOException("没有选中可导入的 PDF 图纸。")
            List(count.value) { index ->
                val item = PointerByReference()
                WindowsFilePicker.checkResult(array.call(8, index, item))
                NativeShellItem(WindowsFilePicker.requirePointer(item)).use { File(it.fileSystemPath()) }
            }
        }
    }
}

private class NativeShellItem(pointer: Pointer) : NativeCom(pointer) {
    fun fileSystemPath(): String = WindowsFilePicker.allocatedText { call(5, 0x80058000.toInt(), it) }
}
