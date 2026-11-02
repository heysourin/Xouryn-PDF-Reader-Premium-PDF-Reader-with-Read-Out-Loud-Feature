package com.novapdf.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimary,
    secondary = AmberAccent,
    background = DarkBackground,
    surface = DarkSurface
)

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    secondary = AmberAccent,
    background = LightBackground,
    surface = LightSurface
)

enum class ReadingThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA,
    NIGHT
}

@Composable
fun NovaPDFTheme(
    readingTheme: ReadingThemeMode = ReadingThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (readingTheme) {
        ReadingThemeMode.SYSTEM -> isSystemInDarkTheme()
        ReadingThemeMode.DARK, ReadingThemeMode.NIGHT -> true
        ReadingThemeMode.LIGHT, ReadingThemeMode.SEPIA -> false
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
