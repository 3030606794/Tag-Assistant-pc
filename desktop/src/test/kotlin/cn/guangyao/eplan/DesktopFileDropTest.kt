package cn.guangyao.eplan

import org.junit.Assert.*
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

class DesktopFileDropTest {
    @Test fun multiplePdfFilesAndMixedBatchKeepOriginalPathsForTheImporter() {
        val files = listOf(File("D:/图纸/机台 Alpha.PDF"), File("D:/图纸/第二份.pdf"), File("D:/图纸/说明.txt"))
        assertEquals(files, localPdfDropFiles(fileTransfer(files)))
    }

    @Test fun webLinksAndTextCannotBecomeLocalImportPaths() {
        assertTrue(localPdfDropFiles(StringSelection("https://example.invalid/private.pdf")).isEmpty())
        assertTrue(localPdfDropFiles(StringSelection("D:/图纸/机台.pdf")).isEmpty())
    }

    @Test fun invalidFileListAndNonPdfOnlyBatchAreRejected() {
        assertTrue(localPdfDropFiles(fileTransfer(listOf("file:///D:/图纸/机台.pdf"))).isEmpty())
        assertTrue(localPdfDropFiles(fileTransfer(listOf(File("D:/图纸/机台.pdf"), "not-a-file"))).isEmpty())
        assertTrue(localPdfDropFiles(fileTransfer(listOf(File("D:/图纸/说明.txt")))).isEmpty())
        assertTrue(localPdfDropFiles(fileTransfer(emptyList<File>())).isEmpty())
    }

    private fun fileTransfer(value: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return value
        }
    }
}
