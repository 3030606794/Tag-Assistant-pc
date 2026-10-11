package cn.guangyao.eplan

/** A machine label is the imported PDF's display name, never text inferred from its pages. */
object DocumentNames {
    fun machineName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "未命名.pdf" }

    /** Only the final PDF extension changes when producing the spreadsheet file. */
    fun exportBaseName(name: String): String = machineName(name)
        .replace(Regex("(?i)\\.pdf$"), "").ifBlank { "未命名" }
}
