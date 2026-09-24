package com.sarvam.voiceassistant.ui

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
 * Boliyan's palette: Indian indigo ink and marigold on warm ivory.
 *
 * Dynamic colour is deliberately off. With it on, Android 12+ repaints the app in the
 * wallpaper's colours, so the app had no look of its own.
 */
object BoliyanColors {
    val Ink = Color(0xFF221F52)
    val InkDeep = Color(0xFF13112A)
    val Marigold = Color(0xFFEBA43C)
    val Vermilion = Color(0xFFD1492E)
    val Peacock = Color(0xFF12706E)
    val Ivory = Color(0xFFFBF6EE)
    val Sand = Color(0xFFF1E8D9)

    /** Marigold dark enough to read as text on ivory. */
    val Turmeric = Color(0xFF8A5300)

    /** The waveform and tile accents cycle through these. */
    val accents = listOf(Marigold, Vermilion, Peacock, Color(0xFF6C63D9))
}

private val LightColors = lightColorScheme(
    primary = BoliyanColors.Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E1FA),
    onPrimaryContainer = BoliyanColors.Ink,
    secondary = BoliyanColors.Turmeric,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE6C2),
    onSecondaryContainer = Color(0xFF4A2F00),
    tertiary = BoliyanColors.Vermilion,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9DDD4),
    onTertiaryContainer = Color(0xFF5A1405),
    background = BoliyanColors.Ivory,
    onBackground = Color(0xFF1D1A26),
    surface = BoliyanColors.Ivory,
    onSurface = Color(0xFF1D1A26),
    surfaceVariant = BoliyanColors.Sand,
    onSurfaceVariant = Color(0xFF5E5868),
    outline = Color(0xFFCFC3B0),
    outlineVariant = Color(0xFFE4D9C7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F2E8),
    surfaceContainer = Color(0xFFF4EDE1),
    surfaceContainerHigh = Color(0xFFEFE6D7),
    surfaceContainerHighest = BoliyanColors.Sand,
)

private val DarkColors = darkColorScheme(
    primary = BoliyanColors.Marigold,
    onPrimary = Color(0xFF2B1B00),
    primaryContainer = Color(0xFF3A3470),
    onPrimaryContainer = Color(0xFFE4E1FA),
    secondary = BoliyanColors.Marigold,
    onSecondary = Color(0xFF2B1B00),
    secondaryContainer = Color(0xFF4A3A12),
    onSecondaryContainer = Color(0xFFFBE6C2),
    tertiary = Color(0xFFF08A6E),
    onTertiary = Color(0xFF3D0A00),
    tertiaryContainer = Color(0xFF6E2414),
    onTertiaryContainer = Color(0xFFF9DDD4),
    background = BoliyanColors.InkDeep,
    onBackground = Color(0xFFEDE8F5),
    surface = BoliyanColors.InkDeep,
    onSurface = Color(0xFFEDE8F5),
    surfaceVariant = Color(0xFF2A2744),
    onSurfaceVariant = Color(0xFFB9B3C9),
    outline = Color(0xFF4B4668),
    outlineVariant = Color(0xFF34304F),
    surfaceContainerLowest = Color(0xFF0E0C21),
    surfaceContainerLow = Color(0xFF18162F),
    surfaceContainer = Color(0xFF1D1A37),
    surfaceContainerHigh = Color(0xFF252246),
    surfaceContainerHighest = Color(0xFF2D2A52),
)

/** Serif for the name and greetings; the platform's Noto fonts cover every Indian script. */
private val Display = FontFamily.Serif

private val BoliyanTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        displayMedium = base.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** Small uppercase heading used to group content, in the accent colour. */
val overline: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.secondary,
    )

private val BoliyanShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun SarvamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BoliyanTypography,
        shapes = BoliyanShapes,
        content = content,
    )
}
