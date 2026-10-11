package cn.guangyao.eplan

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A bundled subprocess reads a snapshot; it never writes workspace or export indices. */
object DesktopPythonTools {
    fun diagnose(data: PocketData, isCancelled: () -> Boolean = { false }): String {
        val resources = File(System.getProperty("eplan.resources.dir")
            ?: System.getProperty("compose.application.resources.dir")
            ?: error("离线工具资源路径不可用，请重新安装完整版本。"))
        val python = File(resources, "python/python.exe")
        val script = File(resources, "tools/offline_tools.py")
        require(python.isFile && script.isFile) { "离线 Python 工具不完整，请重新安装完整版本。" }
        val request = mapOf("version" to 1, "operation" to "diagnose",
            "documents" to data.documents.map { mapOf("name" to it.name, "path" to it.path, "bytes" to it.bytes) },
            "exports" to data.exports.map { mapOf("name" to it.displayName, "path" to it.uri) })
        val process = ProcessBuilder(python.absolutePath, "-I", script.absolutePath)
            .directory(resources).start()
        val output = CompletableFuture.supplyAsync { readBounded(process.inputStream) }
        val errors = CompletableFuture.supplyAsync { readBounded(process.errorStream) }
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(Gson().toJson(request)) }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20)
            while (!process.waitFor(150, TimeUnit.MILLISECONDS)) {
                if (isCancelled() || Thread.currentThread().isInterrupted) error("离线检查已取消。")
                require(System.nanoTime() < deadline) { "离线检查超时，可缩小文件范围后重试。" }
            }
            if (isCancelled()) error("离线检查已取消。")
            val stdout = output.get(10, TimeUnit.SECONDS)
            val stderr = errors.get(10, TimeUnit.SECONDS)
            require(process.exitValue() == 0) { "离线工具退出失败：${stderr.take(500)}" }
            val result = JsonParser.parseString(stdout).asJsonObject
            require(result["version"]?.asInt == 1 && result["ok"]?.asBoolean == true) {
                result["error"]?.asString ?: "离线工具返回了无法读取的结果。"
            }
            return result["report"].asString
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    private fun readBounded(stream: InputStream): String = stream.use {
        val bytes = it.readNBytes(2 * 1024 * 1024 + 1)
        require(bytes.size <= 2 * 1024 * 1024) { "离线检查报告过大，请减少文件数量。" }
        bytes.toString(Charsets.UTF_8)
    }
}
