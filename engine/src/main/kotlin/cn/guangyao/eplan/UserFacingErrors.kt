package cn.guangyao.eplan

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipException

/** Localizes failures at presentation boundaries; document text and stored identifiers are untouched. */
object UserFacingErrors {
    fun describe(error: Throwable, fallback: String = "操作未完成，请重试。"): String {
        // Native exceptions can consist solely of a Chinese pathname; it is not an explanation.
        when (error) {
            is AccessDeniedException, is SecurityException -> return "没有访问此文件或目录的权限，请检查权限或更换目录。"
            is NoSuchFileException, is FileNotFoundException -> return "文件不存在、已被移动或无法打开，请重新选择。"
            is FileAlreadyExistsException -> return "同名文件已存在，请更换名称或目录。"
            is FileSystemException -> return fileSystemMessage(error)
            is org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException ->
                return "此 PDF 已加密或密码不正确，请先使用正确密码解密后再导入。"
        }
        val message = error.message?.trim().orEmpty()
        if (isChineseMessage(message)) return message
        return when (error) {
            is CancellationException, is InterruptedException, is InterruptedIOException -> "任务已取消。"
            is OutOfMemoryError -> "内存不足，请关闭大文件或其他程序后重试。"
            is ZipException -> "文件压缩结构无效或已损坏，无法读取。"
            is EOFException -> "文件内容不完整，无法继续读取。"
            is IOException -> if (fallback != "操作未完成，请重试。") fallback
                else "文件无法读取或写入，可能已损坏、格式不受支持或被其他程序占用。"
            is UnsupportedOperationException -> "当前系统或文件类型暂不支持此操作。"
            else -> error.cause?.takeIf { it !== error }?.let { describe(it, fallback) } ?: fallback
        }
    }

    private fun fileSystemMessage(error: FileSystemException): String {
        val reason = error.reason.orEmpty().lowercase(Locale.ROOT)
        return when {
            "space" in reason || "disk full" in reason -> "磁盘空间不足，请释放空间或更换目录后重试。"
            "used by another process" in reason || "sharing violation" in reason || "being used" in reason ->
                "文件正被其他程序占用，请关闭该文件后重试。"
            "denied" in reason || "permission" in reason -> "没有访问此文件或目录的权限，请检查权限或更换目录。"
            "read-only" in reason || "write protected" in reason -> "文件或目录为只读，请选择可写入的目录。"
            else -> "文件操作未完成，请检查路径、权限和磁盘空间后重试。"
        }
    }

    private fun isChineseMessage(message: String): Boolean {
        if (message.isBlank()) return false
        fun startsChinese(value: String) = value.trimStart().firstOrNull()?.let {
            Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN
        } == true
        if (startsChinese(message)) return true
        val afterFileName = message.replaceFirst(Regex("(?i)^.+\\.(?:pdf|xlsx)[\\s：:]+"), "")
        if (afterFileName != message && startsChinese(afterFileName)) return true
        // Product names and electrical identifiers are intentionally retained before Chinese text.
        val tail = message.replaceFirst(
            Regex("^(?:EPLAN|PDF|Excel|XLSX|CN1|Windows|Python|[A-F](?:/[A-F])*)(?:[\\s：:、]*)"), "")
        return tail != message && startsChinese(tail)
    }
}
