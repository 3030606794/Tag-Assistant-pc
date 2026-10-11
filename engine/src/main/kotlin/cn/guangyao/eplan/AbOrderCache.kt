package cn.guangyao.eplan

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Cache only a verified permutation, never PDF images or a second copy of label content. */
internal class AbOrderCache(cacheDirectory: File, document: ImportedDocument, modified: Long) {
    private val directory = File(cacheDirectory, "offline-pdf/${document.id.filter { it.isLetterOrDigit() || it == '-' }}-${document.bytes}-$modified-desktop-pdfbox2-v6")

    private fun key(page: PdfPageText, devices: List<PdfTextBox>, rotation: Int?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("ab-installation-order-v4/${page.page}/${page.width}/${page.height}/$rotation".toByteArray(Charsets.UTF_8))
        devices.forEach { digest.update("\n${it.text}/${it.x}/${it.y}/${it.width}/${it.height}/${it.baselineX}/${it.baselineY}/${it.angle}".toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(page: PdfPageText, devices: List<PdfTextBox>, rotation: Int?): AbInstallationOrder.OrderResult? = try {
        val file = File(directory, "ab-order-v4-${page.page}-${rotation ?: "auto"}.json")
        if (!file.isFile || file.length() > 512 * 1024) null else {
            val data = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
            require(data.get("key").asString == key(page, devices, rotation))
            val rows = data.getAsJsonArray("positions")
            require(rows.size() == devices.size)
            val seen = HashSet<Int>()
            val positions = rows.map { value ->
                val row = value.asJsonArray
                require(row.size() == 3)
                val index = row[0].asInt; val region = row[1].asInt; val turn = row[2].asInt
                require(index in devices.indices && seen.add(index) && region >= 0 && turn in listOf(0, 90, 180, 270))
                AbInstalledPosition(devices[index], region, turn)
            }
            AbInstallationOrder.OrderResult(positions, true)
        }
    } catch (_: Exception) { null }

    fun write(page: PdfPageText, devices: List<PdfTextBox>, rotation: Int?, result: AbInstallationOrder.OrderResult) {
        if (!result.confident || result.positions.size != devices.size) return
        val indices = devices.withIndex().associate { it.value to it.index }
        val permutation = result.positions.map { indices[it.box] ?: return }
        if (permutation.toSet().size != devices.size) return
        val file = File(directory, "ab-order-v4-${page.page}-${rotation ?: "auto"}.json")
        val temp = File(directory, "${file.name}.tmp")
        try {
            directory.mkdirs()
            val rows = JsonArray()
            result.positions.forEachIndexed { i, p -> rows.add(JsonArray().apply {
                add(permutation[i]); add(p.region); add(p.rotation)
            }) }
            temp.writeText(JsonObject().apply { addProperty("key", key(page, devices, rotation)); add("positions", rows) }.toString(), Charsets.UTF_8)
            if (temp.length() <= 512 * 1024) CacheFiles.replace(temp, file)
        } catch (_: Exception) { /* Geometry caching is optional. */ }
        finally { temp.delete() }
    }
}

/** Windows renameTo cannot replace an existing cache; use a closed-file atomic replacement. */
internal object CacheFiles {
    fun replace(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
