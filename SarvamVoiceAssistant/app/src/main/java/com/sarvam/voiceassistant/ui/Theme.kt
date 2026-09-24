package com.sarvam.voiceassistant.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Indigo = Color(0xFF4F46E5)
private val IndigoLight = Color(0xFFA5B4FC)
private val Teal = Color(0xFF0D9488)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Teal,
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    secondary = Color(0xFF5EEAD4),
)

@Composable
fun SarvamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
