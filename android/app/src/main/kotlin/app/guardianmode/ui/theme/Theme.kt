package app.guardianmode.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.guardianmode.core.DisplaySettings
import app.guardianmode.core.ThemePreference

/** Colors beyond Material's set: the calm "pause" palette and a warm accent. */
@Immutable
data class GuardianColors(
    val pauseSurface: Color,
    val onPauseSurface: Color,
    val accent: Color,
    val onAccent: Color,
    val calm: Color,
    val muted: Color,
    val tileSurface: Color,
    val outline: Color,
)

@Immutable
data class GuardianDimens(
    /** Minimum touch target for the parent. Well above Android's 48 dp. */
    val touchTarget: Dp = 64.dp,
    val gutter: Dp = 20.dp,
    val tileCorner: Dp = 28.dp,
)

val LocalGuardianColors = staticCompositionLocalOf { LightGuardian }
val LocalGuardianDimens = staticCompositionLocalOf { GuardianDimens() }

object Guardian {
    val colors: GuardianColors @Composable get() = LocalGuardianColors.current
    val dimens: GuardianDimens @Composable get() = LocalGuardianDimens.current
}

private val Teal = Color(0xFF1F5E57)
private val TealDeep = Color(0xFF123E39)
private val Coral = Color(0xFFE07A5F)
private val Cream = Color(0xFFFBF7F2)
private val Ink = Color(0xFF1C2421)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EBE6),
    onPrimaryContainer = TealDeep,
    secondary = Coral,
    onSecondary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1ECE5),
    onSurfaceVariant = Color(0xFF4A524F),
    outline = Color(0xFFCFC7BD),
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FD3C5),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF1E4D46),
    onPrimaryContainer = Color(0xFFD6EBE6),
    secondary = Color(0xFFF2A58E),
    onSecondary = Color(0xFF4A1A0C),
    background = Color(0xFF121614),
    onBackground = Color(0xFFE8ECEA),
    surface = Color(0xFF1C2320),
    onSurface = Color(0xFFE8ECEA),
    surfaceVariant = Color(0xFF26302C),
    onSurfaceVariant = Color(0xFFC3CBC7),
    outline = Color(0xFF55605B),
)

private val HighContrastScheme = lightColorScheme(
    primary = Color(0xFF00332D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF00332D),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF7A1F00),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    outline = Color.Black,
)

val LightGuardian = GuardianColors(
    pauseSurface = Color(0xFFFFF3DC),
    onPauseSurface = Color(0xFF3D2A00),
    accent = Coral,
    onAccent = Color.White,
    calm = Color(0xFF2E7D6B),
    muted = Color(0xFF6B7470),
    tileSurface = Color.White,
    outline = Color(0xFFE6DED3),
)

private val DarkGuardian = GuardianColors(
    pauseSurface = Color(0xFF3A2E14),
    onPauseSurface = Color(0xFFFFE9C2),
    accent = Color(0xFFF2A58E),
    onAccent = Color(0xFF4A1A0C),
    calm = Color(0xFF8FD3C5),
    muted = Color(0xFFA3ADA8),
    tileSurface = Color(0xFF1F2724),
    outline = Color(0xFF34403B),
)

private val HighContrastGuardian = GuardianColors(
    pauseSurface = Color(0xFFFFF0C2),
    onPauseSurface = Color.Black,
    accent = Color(0xFF7A1F00),
    onAccent = Color.White,
    calm = Color(0xFF00332D),
    muted = Color(0xFF222222),
    tileSurface = Color.White,
    outline = Color.Black,
)

/**
 * The type scale starts larger than stock Android and is multiplied by the font size
 * the family picked. Android's own font-size setting still applies on top, because
 * every size is in sp.
 */
fun guardianTypography(scale: Float): Typography {
    val s = scale.coerceIn(0.8f, 2.2f)
    fun style(size: Int, weight: FontWeight, line: Float = 1.25f) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = (size * s).sp,
        lineHeight = (size * s * line).sp,
    )
    return Typography(
        displayLarge = style(56, FontWeight.Medium, 1.1f),
        displayMedium = style(44, FontWeight.Medium, 1.1f),
        headlineLarge = style(32, FontWeight.SemiBold),
        headlineMedium = style(26, FontWeight.SemiBold),
        titleLarge = style(22, FontWeight.SemiBold),
        titleMedium = style(19, FontWeight.SemiBold),
        bodyLarge = style(19, FontWeight.Normal, 1.4f),
        bodyMedium = style(17, FontWeight.Normal, 1.4f),
        labelLarge = style(19, FontWeight.SemiBold),
        labelMedium = style(15, FontWeight.Medium),
    )
}

@Composable
fun GuardianTheme(
    display: DisplaySettings = DisplaySettings(),
    content: @Composable () -> Unit,
) {
    val dark = when (display.theme) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val (scheme: ColorScheme, extra: GuardianColors) = when {
        display.highContrast -> HighContrastScheme to HighContrastGuardian
        dark -> DarkScheme to DarkGuardian
        else -> LightScheme to LightGuardian
    }
    CompositionLocalProvider(
        LocalGuardianColors provides extra,
        LocalGuardianDimens provides GuardianDimens(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = guardianTypography(display.fontScale),
            shapes = Shapes(
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(32.dp),
            ),
            content = content,
        )
    }
}
