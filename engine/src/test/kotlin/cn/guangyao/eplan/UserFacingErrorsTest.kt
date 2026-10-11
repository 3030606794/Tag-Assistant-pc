package cn.guangyao.eplan

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException

class UserFacingErrorsTest {
    @Test fun nativeEnglishIoFailureIsExplainedInChinese() {
        val original = "Unexpected end-of-file while reading PDF stream"
        val visible = UserFacingErrors.describe(IOException(original))
        assertTrue(visible.contains(Regex("[\\u4e00-\\u9fff]")))
        assertFalse(visible.contains(original))
        assertFalse(visible.contains("IOException"))
    }

    @Test fun encryptedPdfFailureExplainsThePasswordProblem() {
        val bytes = ByteArrayOutputStream().use { output ->
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                pdf.protect(StandardProtectionPolicy("owner-password", "view-password", AccessPermission()).apply {
                    encryptionKeyLength = 128
                })
                pdf.save(output)
            }
            output.toByteArray()
        }
        val error = assertThrows(InvalidPasswordException::class.java) { PDDocument.load(bytes).close() }
        val visible = UserFacingErrors.describe(error)
        assertTrue(visible.contains("密码") || visible.contains("加密"))
        assertFalse(visible.contains("Cannot decrypt PDF"))
    }

    @Test fun deliberateChineseRuleValidationIsPreserved() {
        val original = "每轴 CN1 容量应为 1–100。"
        assertEquals(original, UserFacingErrors.describe(IllegalArgumentException(original)))
    }

    @Test fun chineseBusinessMessageKeepsTheOriginalEnglishFileName() {
        val original = "Factory Alpha 01.pdf 导入失败，请检查磁盘后重试。"
        assertEquals(original, UserFacingErrors.describe(IOException(original)))
    }

    @Test fun nativePermissionErrorDoesNotMistakeAChinesePathForAnExplanation() {
        val path = "私人资料/机台 01.pdf"
        val visible = UserFacingErrors.describe(AccessDeniedException(path))
        assertNotEquals(path, visible)
        assertTrue(visible.contains("权限") || visible.contains("访问"))
    }

    @Test fun unknownEnglishErrorUsesTheCallersChineseContext() {
        val fallback = "当前操作暂时无法完成，请稍后重试。"
        assertEquals(fallback, UserFacingErrors.describe(RuntimeException("low-level stack message"), fallback))
    }
}
