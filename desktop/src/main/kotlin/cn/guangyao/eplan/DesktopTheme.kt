package cn.guangyao.eplan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// All product colors are defined here; screens use MaterialTheme.colorScheme roles.
private val LightPurple = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF635A75), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA), surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0), surfaceContainerHighest = Color(0xFFE6E0E9),
    surfaceBright = Color(0xFFFEF7FF), surfaceDim = Color(0xFFDED8E1),
    surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
    background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1B20),
    outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFF322F35), inverseOnSurface = Color(0xFFF5EFF7),
    inversePrimary = Color(0xFFD0BCFF), surfaceTint = Color(0xFF6750A4),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val DarkPurple = darkColorScheme(
    primary = Color(0xFFD2BCFC), onPrimary = Color(0xFF32226F),
    primaryContainer = Color(0xFF4C3889), onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCDC1E1), onSecondary = Color(0xFF332B43),
    secondaryContainer = Color(0xFF4B425D), onSecondaryContainer = Color(0xFFE9DDFD),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF4B2030),
    tertiaryContainer = Color(0xFF6C3644), onTertiaryContainer = Color(0xFFFDDAE1),
    surface = Color(0xFF141317), onSurface = Color(0xFFE4E1E7),
    surfaceContainerLowest = Color(0xFF0F0E12),
    surfaceContainerLow = Color(0xFF1C1B1F), surfaceContainer = Color(0xFF201F23),
    surfaceContainerHigh = Color(0xFF2B292D), surfaceContainerHighest = Color(0xFF363438),
    surfaceBright = Color(0xFF3A383D), surfaceDim = Color(0xFF141317),
    surfaceVariant = Color(0xFF494550), onSurfaceVariant = Color(0xFFC9C4D1),
    background = Color(0xFF141317), onBackground = Color(0xFFE4E1E7),
    outline = Color(0xFF938F9B), outlineVariant = Color(0xFF494550),
    inverseSurface = Color(0xFFE4E1E7), inverseOnSurface = Color(0xFF313034),
    inversePrimary = Color(0xFF6750A4), surfaceTint = Color(0xFFD2BCFC),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
)

private val Roboto = FontFamily(
    Font("fonts/roboto_regular.ttf", FontWeight.Normal),
    Font("fonts/roboto_medium.ttf", FontWeight.Medium),
    Font("fonts/roboto_bold.ttf", FontWeight.Bold),
)

// The font-family constructor preserves all standard Material 3 type sizes and weights.
private val PocketTypography = Typography(fontFamily = Roboto)
private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
    largeIncreased = RoundedCornerShape(12.dp),
    extraLargeIncreased = RoundedCornerShape(12.dp),
    extraExtraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun DesktopTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) DarkPurple else LightPurple,
        motionScheme = MotionScheme.expressive(),
        typography = PocketTypography,
        shapes = PocketShapes,
        content = content,
    )
}
