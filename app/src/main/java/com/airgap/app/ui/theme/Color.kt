package com.airgap.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class AppThemeMode(val title: String, val description: String) {
    DEFAULT("AirGap Dark", "Standard slate dark with customizable accent"),
    OLED("AMOLED Pure Black", "True #000000 pitch black for maximum battery saving"),
    LIGHT("AirGap Light", "Clean, high-contrast light theme"),
    CYBERPUNK("Cyber Amber", "Deep midnight obsidian with warm amber accents")
}

enum class AccentColorChoice(val title: String, val primary: Color, val secondary: Color) {
    LIME("Neon Lime", Color(0xFFC4F042), Color(0xFF64D2FF)),
    EMERALD("Emerald", Color(0xFF55E6A5), Color(0xFF38BDF8)),
    CYAN("Electric Cyan", Color(0xFF38BDF8), Color(0xFFC4F042)),
    AMBER("Solar Amber", Color(0xFFFFB800), Color(0xFFFF6D00)),
    VIOLET("Cyber Violet", Color(0xFFC084FC), Color(0xFF38BDF8)),
    CORAL("Hot Coral", Color(0xFFFF6B6B), Color(0xFFFFB800))
}

// Base Neutral Colors
val BgDark = Color(0xFF0D0F12)
val SurfaceDark = Color(0xFF16191E)
val SurfaceVariantDark = Color(0xFF1E222A)
val SurfaceElevatedDark = Color(0xFF262C36)

val AccentWarning = Color(0xFFFFB340)
val AccentError = Color(0xFFFF5252)
val AccentSuccess = Color(0xFF55E6A5)

val TextPrimary = Color(0xFFF3F5F7)
val TextSecondary = Color(0xFF9CA3AF)
val TextTertiary = Color(0xFF6B7280)
val BorderSubtle = Color(0xFF272C35)

fun createColorScheme(themeMode: AppThemeMode, accent: AccentColorChoice): ColorScheme {
    val prim = accent.primary
    val sec = accent.secondary

    return when (themeMode) {
        AppThemeMode.DEFAULT -> darkColorScheme(
            primary = prim,
            onPrimary = Color(0xFF111400),
            primaryContainer = prim.copy(alpha = 0.2f),
            onPrimaryContainer = prim,
            secondary = sec,
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
        AppThemeMode.OLED -> darkColorScheme(
            primary = prim,
            onPrimary = Color.Black,
            primaryContainer = prim.copy(alpha = 0.15f),
            onPrimaryContainer = prim,
            secondary = sec,
            onSecondary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color(0xFF0A0A0A),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF141414),
            onSurfaceVariant = Color(0xFFB0B0B0),
            outline = Color(0xFF242424),
            error = AccentError,
            onError = Color.White
        )
        AppThemeMode.LIGHT -> lightColorScheme(
            primary = prim,
            onPrimary = Color.White,
            primaryContainer = prim.copy(alpha = 0.15f),
            onPrimaryContainer = Color(0xFF191C20),
            secondary = sec,
            onSecondary = Color.White,
            background = Color(0xFFF8F9FB),
            onBackground = Color(0xFF191C20),
            surface = Color.White,
            onSurface = Color(0xFF191C20),
            surfaceVariant = Color(0xFFE8ECF2),
            onSurfaceVariant = Color(0xFF44474E),
            outline = Color(0xFFD0D5DD),
            error = Color(0xFFBA1A1A),
            onError = Color.White
        )
        AppThemeMode.CYBERPUNK -> darkColorScheme(
            primary = AccentColorChoice.AMBER.primary,
            onPrimary = Color(0xFF261A00),
            primaryContainer = Color(0xFF4D3300),
            onPrimaryContainer = Color(0xFFFFE082),
            secondary = AccentColorChoice.AMBER.secondary,
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
    }
}
