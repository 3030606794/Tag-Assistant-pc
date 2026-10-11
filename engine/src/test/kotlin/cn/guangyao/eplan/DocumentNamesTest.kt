package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentNamesTest {
    @Test fun machineLabelsCopyTheCompleteFileNameWithoutAbbreviating() {
        listOf("B06B07-1.pdf", "印度预热压.PDF", "2026 C1&C2 - 转接片焊接机 v2.pdf", "项目.版本.图纸.pdf")
            .forEach { name -> assertEquals(name, DocumentNames.machineName(name)) }
    }

    @Test fun pathsAreRemovedWithoutChangingTheDisplayName() {
        assertEquals("B06B07-1.pdf", DocumentNames.machineName("D:\\图纸\\B06B07-1.pdf"))
        assertEquals("项目 v2.PDF", DocumentNames.machineName("/storage/emulated/0/项目 v2.PDF"))
    }

    @Test fun exportRemovesOnlyTheFinalPdfExtension() {
        assertEquals("B06B07-1", DocumentNames.exportBaseName("B06B07-1.pdf"))
        assertEquals("项目.pdf.v2", DocumentNames.exportBaseName("项目.pdf.v2.PDF"))
        assertEquals("图纸.v2", DocumentNames.exportBaseName("图纸.v2"))
        assertEquals("空 格", DocumentNames.exportBaseName("/a/空 格.pdf"))
    }

    @Test fun missingNamesHaveUsableFallbacks() {
        assertEquals("未命名.pdf", DocumentNames.machineName(""))
        assertEquals("未命名", DocumentNames.exportBaseName(""))
        assertEquals("未命名", DocumentNames.exportBaseName(".pdf"))
    }
}
