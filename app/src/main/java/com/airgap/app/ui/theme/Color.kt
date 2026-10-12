package com.airgap.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class AppThemeMode(val title: String, val description: String) {
    DEFAULT("AirGap Dark", "Standard slate dark with neon lime accent"),
    OLED("OLED Pure Black", "True #000000 pitch black for battery saving"),
    LIGHT("AirGap Light", "Clean, high-contrast light theme"),
    CYBERPUNK("Cyber Amber", "Deep midnight background with warm amber accents")
}

// 1. AirGap Default Dark
val BgDark = Color(0xFF0D0F12)
val SurfaceDark = Color(0xFF16191E)
val SurfaceVariantDark = Color(0xFF1E222A)
val SurfaceElevatedDark = Color(0xFF262C36)

val AccentPrimary = Color(0xFFC4F042)
val AccentSecondary = Color(0xFF64D2FF)
val AccentWarning = Color(0xFFFFB340)
val AccentError = Color(0xFFFF5252)
val AccentSuccess = Color(0xFF55E6A5)

val TextPrimary = Color(0xFFF3F5F7)
val TextSecondary = Color(0xFF9CA3AF)
val TextTertiary = Color(0xFF6B7280)
val BorderSubtle = Color(0xFF272C35)

val DefaultColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color(0xFF111400),
    primaryContainer = Color(0xFF2B3A0A),
    onPrimaryContainer = Color(0xFFDEFFA6),
    secondary = AccentSecondary,
    onSecondary = Color(0xFF003548),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = AccentError,
    onError = Color.White
)

// 2. OLED Pure Black
val OledColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1A2405),
    onPrimaryContainer = Color(0xFFDEFFA6),
    secondary = AccentSecondary,
    onSecondary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF080808),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF222222),
    error = AccentError,
    onError = Color.White
)

// 3. AirGap Light
val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4C7500),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFFA1),
    onPrimaryContainer = Color(0xFF142000),
    secondary = Color(0xFF006684),
    onSecondary = Color.White,
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE8ECF2),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFFD0D5DD),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

// 4. Cyber Amber Theme
val AmberPrimary = Color(0xFFFFB800)
val AmberSecondary = Color(0xFFFF6D00)
val CyberColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = Color(0xFF261A00),
    primaryContainer = Color(0xFF4D3300),
    onPrimaryContainer = Color(0xFFFFE082),
    secondary = AmberSecondary,
    onSecondary = Color(0xFF2E1000),
    background = Color(0xFF0A090D),
    onBackground = Color(0xFFF7F5F0),
    surface = Color(0xFF141219),
    onSurface = Color(0xFFF7F5F0),
    surfaceVariant = Color(0xFF211D29),
    onSurfaceVariant = Color(0xFFAAA5B3),
    outline = Color(0xFF332D3F),
    error = AccentError,
    onError = Color.White
)

fun getThemeColorScheme(themeMode: AppThemeMode): ColorScheme {
    return when (themeMode) {
        AppThemeMode.DEFAULT -> DefaultColorScheme
        AppThemeMode.OLED -> OledColorScheme
        AppThemeMode.LIGHT -> LightColorScheme
        AppThemeMode.CYBERPUNK -> CyberColorScheme
    }
}
