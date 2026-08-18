package dev.codexremote.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexremote.app.data.UiPreferences

private val LightColors = lightColorScheme(
    primary = Color(0xFF242424),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF242424),
    secondary = Color(0xFF5F6368),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF303030),
    tertiary = Color(0xFF9A7200),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE9A6),
    onTertiaryContainer = Color(0xFF3F2F00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF202020),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202020),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    surfaceVariant = Color(0xFFE7E7E7),
    onSurfaceVariant = Color(0xFF494949),
    surfaceTint = Color.Transparent,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDEDEDE),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF4F4F4),
    inversePrimary = Color(0xFFF4F4F4),
    outline = Color(0xFF747474),
    outlineVariant = Color(0xFFC9C9C9),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8E8E8),
    onPrimary = Color(0xFF242424),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFC9C9C9),
    onSecondary = Color(0xFF282828),
    secondaryContainer = Color(0xFF383838),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFE8C547),
    onTertiary = Color(0xFF3D3100),
    tertiaryContainer = Color(0xFF584900),
    onTertiaryContainer = Color(0xFFFFE990),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE8E8E8),
    surfaceContainerLowest = Color(0xFF181818),
    surfaceContainerLow = Color(0xFF242424),
    surfaceContainer = Color(0xFF292929),
    surfaceContainerHigh = Color(0xFF343434),
    surfaceContainerHighest = Color(0xFF3C3C3C),
    surfaceVariant = Color(0xFF3B3B3B),
    onSurfaceVariant = Color(0xFFC8C8C8),
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF3B3B3B),
    surfaceDim = Color(0xFF141414),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF242424),
    inversePrimary = Color(0xFF242424),
    outline = Color(0xFF969696),
    outlineVariant = Color(0xFF505050),
    scrim = Color.Black,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

private val BaseMonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp,
)

private val LocalAppFontScale = staticCompositionLocalOf { 1f }

val MonoTextStyle: TextStyle
    @Composable get() = BaseMonoTextStyle.scaled(LocalAppFontScale.current)

@Composable
fun CodexRemoteTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    val scale = fontScale.coerceIn(UiPreferences.MIN_FONT_SCALE, UiPreferences.MAX_FONT_SCALE)
    CompositionLocalProvider(LocalAppFontScale provides scale) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
            typography = AppTypography.scaled(scale),
            shapes = AppShapes,
            content = content,
        )
    }
}

private fun Typography.scaled(factor: Float): Typography = copy(
    displayLarge = displayLarge.scaled(factor),
    displayMedium = displayMedium.scaled(factor),
    displaySmall = displaySmall.scaled(factor),
    headlineLarge = headlineLarge.scaled(factor),
    headlineMedium = headlineMedium.scaled(factor),
    headlineSmall = headlineSmall.scaled(factor),
    titleLarge = titleLarge.scaled(factor),
    titleMedium = titleMedium.scaled(factor),
    titleSmall = titleSmall.scaled(factor),
    bodyLarge = bodyLarge.scaled(factor),
    bodyMedium = bodyMedium.scaled(factor),
    bodySmall = bodySmall.scaled(factor),
    labelLarge = labelLarge.scaled(factor),
    labelMedium = labelMedium.scaled(factor),
    labelSmall = labelSmall.scaled(factor),
)

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaled(factor),
    lineHeight = lineHeight.scaled(factor),
    letterSpacing = 0.sp,
)

private fun TextUnit.scaled(factor: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else (value * factor).sp
