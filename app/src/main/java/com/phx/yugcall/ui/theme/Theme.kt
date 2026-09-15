package com.phx.yugcall.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    secondary = Teal80,
    onSecondary = Teal20,
    secondaryContainer = Teal40,
    onSecondaryContainer = Teal90,
    tertiary = Coral80,
    onTertiary = Coral20,
    tertiaryContainer = Coral40,
    onTertiaryContainer = Coral90,
    error = ErrorRed80,
    onError = ErrorRed20,
    background = Neutral4,
    onBackground = Neutral90,
    surface = Neutral4,
    onSurface = Neutral90,
    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral6,
    surfaceContainer = Neutral10,
    surfaceContainerHigh = Neutral12,
    surfaceContainerHighest = Neutral17,
    surfaceVariant = Neutral22,
    onSurfaceVariant = NeutralVariant80,
    outline = NeutralVariant60,
    outlineVariant = Neutral22,
)

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal10,
    tertiary = Coral40,
    onTertiary = Color.White,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Coral10,
    error = ErrorRed40,
    onError = Color.White,
    errorContainer = ErrorRed90,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceContainerLowest = Neutral99,
    surfaceContainerLow = Neutral95,
    surfaceContainer = SurfaceLight1,
    surfaceContainerHigh = SurfaceLight2,
    surfaceContainerHighest = SurfaceLight3,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
)

@Composable
fun YugCallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic colour is on by default so the app adopts the user's wallpaper
    // palette on Android 12+, which is the expected behaviour for a current
    // Android app. The scheme above is the pre-12 fallback.
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
