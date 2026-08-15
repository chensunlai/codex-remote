package dev.codexremote.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F2E8),
    onPrimaryContainer = Color(0xFF063C32),
    secondary = Color(0xFF53606E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE3EA),
    onSecondaryContainer = Color(0xFF29333D),
    tertiary = Color(0xFF9A5B21),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDBE),
    onTertiaryContainer = Color(0xFF522900),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF7F9F7),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6F4),
    surfaceContainer = Color(0xFFEEF1EE),
    surfaceContainerHigh = Color(0xFFE8EBE8),
    surfaceContainerHighest = Color(0xFFE1E5E1),
    surfaceVariant = Color(0xFFE1E5E1),
    onSurfaceVariant = Color(0xFF414845),
    outline = Color(0xFF717975),
    outlineVariant = Color(0xFFC1C9C4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF65D6B8),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF075044),
    onPrimaryContainer = Color(0xFFA0F2DC),
    secondary = Color(0xFFBAC7D3),
    onSecondary = Color(0xFF25313C),
    secondaryContainer = Color(0xFF3B4854),
    onSecondaryContainer = Color(0xFFD6E3EF),
    tertiary = Color(0xFFF0AA6A),
    onTertiary = Color(0xFF512B07),
    tertiaryContainer = Color(0xFF703F13),
    onTertiaryContainer = Color(0xFFFFDCC0),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E4E0),
    surface = Color(0xFF191D1A),
    onSurface = Color(0xFFE1E4E0),
    surfaceContainerLowest = Color(0xFF0D100E),
    surfaceContainerLow = Color(0xFF171B18),
    surfaceContainer = Color(0xFF1D211E),
    surfaceContainerHigh = Color(0xFF272C28),
    surfaceContainerHighest = Color(0xFF323733),
    surfaceVariant = Color(0xFF414845),
    onSurfaceVariant = Color(0xFFC1C9C4),
    outline = Color(0xFF8B938F),
    outlineVariant = Color(0xFF414845),
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

val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp,
)

@Composable
fun CodexRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
