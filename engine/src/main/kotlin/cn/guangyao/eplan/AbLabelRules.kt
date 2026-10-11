package cn.guangyao.eplan

/** The five missing-caption substitutions explicitly specified by the user, for B only. */
internal object AbLabelRules {
    fun missingModuleCaption(device: String): String? {
        val id = EplanParsing.deviceId(device, requireLeader = false) ?: return null
        return when (EplanParsing.deviceCategory(id)) {
            "ECT" -> "耦合器"
            "AI" -> "模拟量输入"
            "AO" -> "模拟量输出"
            "DI" -> "数字量输入"
            "DO" -> "数字量输出"
            else -> null
        }
    }

    /** Retain supplementary resistor views, after the cabinet's actual installation pages. */
    fun supplementaryResistors(scope: PdfPageScope, devices: List<String>): Boolean =
        devices.isNotEmpty() && devices.all { EplanParsing.deviceCategory(it) == "R" } &&
            Regex("原理|轮廓|附加|补充").containsMatchIn(scope.title) &&
            !Regex("安装板布局|布局图|安装板布置|柜内布置").containsMatchIn(scope.title)
}
