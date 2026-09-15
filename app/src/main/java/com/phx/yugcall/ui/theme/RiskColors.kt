package com.phx.yugcall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.phx.yugcall.RiskLevel

/**
 * Semantic risk colours.
 *
 * Deliberately *not* derived from the dynamic colour scheme: "this may be a
 * synthetic voice" must not change hue because the user changed wallpaper.
 * Each level carries its own container, content and accent so contrast holds in
 * both themes.
 */
@Immutable
data class RiskColors(
    val container: Color,
    val content: Color,
    val accent: Color,
    val track: Color,
)

private val LightLow = RiskColors(
    container = Color(0xFFDCF5E3), content = Color(0xFF0B3D1E),
    accent = Color(0xFF1B7F4B), track = Color(0xFFB9E7C9),
)
private val LightMedium = RiskColors(
    container = Color(0xFFFFF1D6), content = Color(0xFF5C3B00),
    accent = Color(0xFFB07400), track = Color(0xFFFFE0A8),
)
private val LightHigh = RiskColors(
    container = Color(0xFFFFE3E1), content = Color(0xFF6E1512),
    accent = Color(0xFFC2352E), track = Color(0xFFFFC7C3),
)
private val LightNeutral = RiskColors(
    container = Color(0xFFE9E7F0), content = Color(0xFF3A3843),
    accent = Color(0xFF6B6779), track = Color(0xFFD5D2DE),
)

private val DarkLow = RiskColors(
    container = Color(0xFF10301C), content = Color(0xFFA6E7BF),
    accent = Color(0xFF5FD18C), track = Color(0xFF1E4A30),
)
private val DarkMedium = RiskColors(
    container = Color(0xFF332612), content = Color(0xFFFFDFA8),
    accent = Color(0xFFF0BC5E), track = Color(0xFF4A3A1C),
)
private val DarkHigh = RiskColors(
    container = Color(0xFF3A1817), content = Color(0xFFFFB9B4),
    accent = Color(0xFFFF8C84), track = Color(0xFF522422),
)
private val DarkNeutral = RiskColors(
    container = Color(0xFF232130), content = Color(0xFFCFCAD9),
    accent = Color(0xFF9A94AB), track = Color(0xFF32303E),
)

@Composable
@ReadOnlyComposable
fun riskColors(level: RiskLevel): RiskColors {
    val dark = isSystemInDarkTheme()
    return when (level) {
        RiskLevel.LOW -> if (dark) DarkLow else LightLow
        RiskLevel.MEDIUM -> if (dark) DarkMedium else LightMedium
        RiskLevel.HIGH -> if (dark) DarkHigh else LightHigh
    }
}

/** Used for idle, loading, listening and "nothing to report" states. */
@Composable
@ReadOnlyComposable
fun neutralRiskColors(): RiskColors = if (isSystemInDarkTheme()) DarkNeutral else LightNeutral
