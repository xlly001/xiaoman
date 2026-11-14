package dev.xiaoman.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Open Muse inspired visual language: near-white paper, soft grey bubbles, a single blue accent,
 * system sans-serif type and generous 22dp cards. Deliberately independent of the phone wallpaper.
 */
private val Ink = Color(0xFF111113)
private val MutedInk = Color(0xFF6E6E73)
private val Paper = Color(0xFFFCFCFC)
private val BubbleGrey = Color(0xFFEAEAEB)
private val BubbleBlue = Color(0xFFC9E5FF)
private val MuseBlue = Color(0xFF1A62D6)

private val LightScheme = lightColorScheme(
    primary = MuseBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = BubbleBlue,
    onPrimaryContainer = Color(0xFF0B3A82),
    secondary = MutedInk,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F2),
    onSecondaryContainer = Color(0xFF3A3A3C),
    tertiary = Color(0xFF147A4B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF3E7),
    onTertiaryContainer = Color(0xFF0B3D27),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF2F2F4),
    onSurfaceVariant = MutedInk,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = BubbleGrey,
    surfaceContainerHigh = Color(0xFFE3E3E5),
    surfaceContainerHighest = Color(0xFFDCDCDE),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFEAEAEB),
    error = Color(0xFFD70015),
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = Color(0xFF5C0006),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6BB0FF),
    onPrimary = Color(0xFF001B3D),
    primaryContainer = Color(0xFF0B3A82),
    onPrimaryContainer = BubbleBlue,
    secondary = Color(0xFFA1A1A6),
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFF7BD8A8),
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF0E4D30),
    onTertiaryContainer = Color(0xFFDDF3E7),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFA1A1A6),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF2C2C2E),
    surfaceContainerHigh = Color(0xFF3A3A3C),
    surfaceContainerHighest = Color(0xFF48484A),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF3A3A3C),
    error = Color(0xFFFF6961),
    errorContainer = Color(0xFF5C0006),
    onErrorContainer = Color(0xFFFFE5E3),
)

private val MuseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val MuseTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun XiaomanTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = MuseTypography, shapes = MuseShapes, content = content)
}
