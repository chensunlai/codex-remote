package dev.codexremote.app.ui

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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.model.AppTheme

// Match the official extension's semantic surfaces and foreground-based controls.
private fun editorColors(dark: Boolean, background: Color, foreground: Color, elevated: Color, accent: Color) =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = foreground,
        onPrimary = background,
        primaryContainer = foreground.copy(alpha = 0.10f).compositeOver(background),
        onPrimaryContainer = foreground,
        secondary = foreground.copy(alpha = 0.65f).compositeOver(background),
        onSecondary = background,
        secondaryContainer = foreground.copy(alpha = 0.05f).compositeOver(background),
        onSecondaryContainer = foreground,
        tertiary = accent,
        onTertiary = Color.White,
        tertiaryContainer = accent.copy(alpha = 0.12f).compositeOver(background),
        onTertiaryContainer = foreground,
        background = background,
        onBackground = foreground,
        surface = elevated,
        onSurface = foreground,
        surfaceContainerLowest = background,
        surfaceContainerLow = foreground.copy(alpha = 0.025f).compositeOver(background),
        surfaceContainer = foreground.copy(alpha = 0.05f).compositeOver(background),
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = foreground.copy(alpha = 0.10f).compositeOver(background),
        surfaceVariant = foreground.copy(alpha = 0.05f).compositeOver(background),
        onSurfaceVariant = foreground.copy(alpha = 0.65f).compositeOver(background),
        surfaceTint = Color.Transparent,
        surfaceBright = elevated,
        surfaceDim = background,
        inverseSurface = foreground,
        inverseOnSurface = background,
        inversePrimary = background,
        outline = foreground.copy(alpha = 0.12f).compositeOver(background),
        outlineVariant = foreground.copy(alpha = 0.08f).compositeOver(background),
        scrim = Color.Black,
    )

private val LightColors = editorColors(false, Color(0xFFFFFFFF), Color(0xFF333333), Color(0xFFF3F3F3), Color(0xFF006AB1))
private val DarkColors = editorColors(true, Color(0xFF1E1E1E), Color(0xFFD4D4D4), Color(0xFF252526), Color(0xFF3794FF))
private val AnthropicColors = editorColors(false, Color(0xFFFAF9F5), Color(0xFF302F2B), Color(0xFFF0EEE6), Color(0xFFC96442))

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
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
fun CodexRemoteTheme(fontScale: Float = 1f, theme: AppTheme = AppTheme.DARK, content: @Composable () -> Unit) {
    val scale = fontScale.coerceIn(UiPreferences.MIN_FONT_SCALE, UiPreferences.MAX_FONT_SCALE)
    CompositionLocalProvider(LocalAppFontScale provides scale) {
        MaterialTheme(
            colorScheme = when (theme) {
                AppTheme.DARK -> DarkColors
                AppTheme.LIGHT -> LightColors
                AppTheme.ANTHROPIC -> AnthropicColors
            },
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
