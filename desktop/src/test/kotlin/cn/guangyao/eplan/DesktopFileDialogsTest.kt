package cn.guangyao.eplan

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Real Windows COM calls only; these tests never invoke IModalWindow.Show. */
class DesktopFileDialogsTest {
    @Test fun nativePdfDialogAcceptsChineseTextAndMultiSelectFilter() {
        assumeTrue(DesktopFileDialogs.isWindows)
        val directory = Files.createTempDirectory("Eplan-文件选择-")
        try {
            val file = Files.write(directory.resolve("图纸 中文名称.pdf"), "%PDF-1.4\n".toByteArray())
            val result = DesktopFileDialogs.verifyWindowsBindings(false, file.toFile())
            assertTrue(result.staApartment)
            assertEquals("导入电气图纸 PDF", result.title)
            assertEquals("导入", result.confirmation)
            // GetFileName and GetDisplayName exercise native UTF-16 round-trips.
            assertEquals("电气图纸 中文路径.pdf", result.unicodeFileName)
            assertTrue(Files.isSameFile(directory, Path.of(result.initialDirectory)))
            assertTrue(Files.isSameFile(file, Path.of(result.shellItemPath)))
            assertEquals("*.pdf", result.pdfFilter)
            assertEquals(1, result.fileTypeIndex)
            assertTrue(result.options and 0x40 != 0) // FOS_FORCEFILESYSTEM
            assertTrue(result.options and 0x200 != 0) // FOS_ALLOWMULTISELECT
            assertTrue(result.options and 0x800 != 0) // FOS_PATHMUSTEXIST
            assertTrue(result.options and 0x1000 != 0) // FOS_FILEMUSTEXIST
            assertEquals(0, result.options and 0x20) // no FOS_PICKFOLDERS
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun nativeFolderDialogStartsAtExistingExportDirectory() {
        assumeTrue(DesktopFileDialogs.isWindows)
        val directory = Files.createTempDirectory("Eplan-导出目录-")
        try {
            val result = DesktopFileDialogs.verifyWindowsBindings(true, directory.toFile())
            assertTrue(result.staApartment)
            assertEquals("选择 Excel 导出目录", result.title)
            assertEquals("选择文件夹", result.confirmation)
            assertEquals("导出文件夹 中文路径", result.unicodeFileName)
            assertTrue(Files.isSameFile(directory, Path.of(result.initialDirectory)))
            assertTrue(Files.isSameFile(directory, Path.of(result.shellItemPath)))
            assertNull(result.pdfFilter)
            assertNull(result.fileTypeIndex)
            assertTrue(result.options and 0x20 != 0) // FOS_PICKFOLDERS
            assertTrue(result.options and 0x40 != 0) // FOS_FORCEFILESYSTEM
            assertTrue(result.options and 0x800 != 0) // FOS_PATHMUSTEXIST
            assertEquals(0, result.options and 0x200) // single folder only
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun cancelledDialogIsDistinctFromSuccessOrNativeFailure() {
        assertTrue(DesktopFileDialogs.isCancellation(0x800704C7.toInt()))
        assertFalse(DesktopFileDialogs.isCancellation(0))
        assertFalse(DesktopFileDialogs.isCancellation(0x80004005.toInt()))
    }
}
