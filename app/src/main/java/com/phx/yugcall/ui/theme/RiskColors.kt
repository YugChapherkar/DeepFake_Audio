package com.phx.yugcall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.phx.yugcall.RiskLevel

/**
 * Risk colours for both themes.
 *
 * The first version of this screen hardcoded light-mode hex values against the
 * default `onSurface` text colour, which rendered as light text on a pale green
 * card in dark mode. Each level now carries its own container and content
 * colour so contrast holds either way.
 */
@Immutable
data class RiskColors(
    val container: Color,
    val content: Color,
    val accent: Color,
)

private val LightLow = RiskColors(Color(0xFFE8F5E9), Color(0xFF1B5E20), Color(0xFF2E7D32))
private val LightMedium = RiskColors(Color(0xFFFFF8E1), Color(0xFF7A4F01), Color(0xFFB26A00))
private val LightHigh = RiskColors(Color(0xFFFFEBEE), Color(0xFF8E1B1B), Color(0xFFC62828))
private val LightNeutral = RiskColors(Color(0xFFEEEEEE), Color(0xFF37474F), Color(0xFF546E7A))

private val DarkLow = RiskColors(Color(0xFF16301A), Color(0xFFA5D6A7), Color(0xFF81C784))
private val DarkMedium = RiskColors(Color(0xFF332812), Color(0xFFFFE082), Color(0xFFFFCA28))
private val DarkHigh = RiskColors(Color(0xFF3A1A1C), Color(0xFFEF9A9A), Color(0xFFE57373))
private val DarkNeutral = RiskColors(Color(0xFF26292B), Color(0xFFCFD8DC), Color(0xFF90A4AE))

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

/** Used for "listening", "analysing" and "nothing to report" states. */
@Composable
@ReadOnlyComposable
fun neutralRiskColors(): RiskColors = if (isSystemInDarkTheme()) DarkNeutral else LightNeutral
