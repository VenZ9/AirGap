package com.airgap.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

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

val AirGapColorScheme = darkColorScheme(
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
