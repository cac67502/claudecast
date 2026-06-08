package com.aide.claudecast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* ----- Claude palette ----- */
// Signature clay / terracotta
val ClaudeClay = Color(0xFFD97757)
val ClaudeClayDeep = Color(0xFFC15F3C)
val ClaudeClayDim = Color(0xFFA9512E)

// Warm neutrals
val Cream = Color(0xFFFAF9F5)
val CreamDim = Color(0xFFF0EEE6)
val Ink = Color(0xFF1A1815)

// Warm darks
val WarmBlack = Color(0xFF1F1E1D)
val WarmSurface = Color(0xFF262624)
val WarmSurfaceHi = Color(0xFF30302E)
val WarmOutline = Color(0xFF45433E)

// Accents
val Tan = Color(0xFFC9AE94)
val Sage = Color(0xFF8A9A7B)

private val DarkColors = darkColorScheme(
    primary = ClaudeClay,
    onPrimary = Color(0xFF2B1505),
    primaryContainer = ClaudeClayDim,
    onPrimaryContainer = Cream,
    secondary = Tan,
    onSecondary = Color(0xFF26201A),
    tertiary = Sage,
    onTertiary = Color(0xFF1B2117),
    background = WarmBlack,
    onBackground = Color(0xFFECE9E2),
    surface = WarmSurface,
    onSurface = Color(0xFFECE9E2),
    surfaceVariant = WarmSurfaceHi,
    onSurfaceVariant = Color(0xFFB8B2A7),
    outline = WarmOutline,
    outlineVariant = Color(0xFF35332F),
    error = Color(0xFFE5736B),
    onError = Color(0xFF2B0F0C)
)

private val LightColors = lightColorScheme(
    primary = ClaudeClayDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DACB),
    onPrimaryContainer = Color(0xFF45200F),
    secondary = Color(0xFF8C6F54),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5F7050),
    onTertiary = Color(0xFFFFFFFF),
    background = Cream,
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = CreamDim,
    onSurfaceVariant = Color(0xFF5C574E),
    outline = Color(0xFFCBC4B6),
    outlineVariant = Color(0xFFE4DECF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

val ClaudeTypography = Typography()

enum class ThemePref { System, Dark, Light }

@Composable
fun ClaudeCastTheme(
    pref: ThemePref = ThemePref.Dark,
    content: @Composable () -> Unit
) {
    val dark = when (pref) {
        ThemePref.System -> isSystemInDarkTheme()
        ThemePref.Dark -> true
        ThemePref.Light -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ClaudeTypography,
        content = content
    )
}
