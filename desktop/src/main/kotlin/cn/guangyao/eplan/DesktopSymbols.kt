package cn.guangyao.eplan

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

/** The original Material Symbols Rounded vectors, with desktop resource loading. */
@Composable
fun Symbol(name: String, contentDescription: String? = null, modifier: Modifier = Modifier,
           tint: Color = LocalContentColor.current, filled: Boolean = false) {
    val stem = name + if (filled) "_fill1" else ""
    val resource = "symbols/$stem.svg".takeIf {
        Thread.currentThread().contextClassLoader.getResource(it) != null
    } ?: "symbols/$name.svg".takeIf {
        Thread.currentThread().contextClassLoader.getResource(it) != null
    } ?: "symbols/info.svg"
    Icon(painterResource(resource), contentDescription, modifier, tint)
}
